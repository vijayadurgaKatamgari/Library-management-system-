package com.library;

import com.google.gson.Gson;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.Connection;
import java.util.*;

/** One servlet exposing the JSON API used by index.html (all data access via JDBC). */
@WebServlet("/api/*")
public class ApiServlet extends HttpServlet {
    static final Gson G = new Gson();
    static final int FINE_PER_DAY = 5, LOAN_DAYS = 14;
    static final String FINE = "GREATEST(DATEDIFF(IFNULL(i.return_date,CURDATE()),i.due_date),0)*" + FINE_PER_DAY;
    static final Set<String> METHODS = Set.of("Cash", "UPI", "Card", "NetBanking", "Wallet");

    @Override
    protected void service(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        rq.setCharacterEncoding("UTF-8");
        rs.setContentType("application/json;charset=UTF-8");
        Object out;
        try {
            String route = rq.getMethod() + " " + (rq.getPathInfo() == null ? "" : rq.getPathInfo());
            switch (route) {
                case "GET /stats":   out = stats(); break;
                case "GET /books": {
                    String q = "%" + (rq.getParameter("q") == null ? "" : rq.getParameter("q")) + "%";
                    out = DB.query("SELECT * FROM books WHERE title LIKE ? OR author LIKE ? OR category LIKE ? ORDER BY title", q, q, q);
                    break;
                }
                case "POST /books":
                    DB.update("INSERT INTO books(title,author,category,total,available) VALUES(?,?,?,?,?)",
                            s(rq, "title"), s(rq, "author"), s(rq, "category"), n(rq, "copies"), n(rq, "copies"));
                    out = ok(); break;
                case "POST /books/delete":
                    DB.update("DELETE FROM books WHERE id=?", n(rq, "id")); out = ok(); break;
                case "GET /members":
                    out = DB.query("SELECT m.*, (SELECT COUNT(*) FROM issues i WHERE i.member_id=m.id AND i.return_date IS NULL) AS borrowed FROM members m ORDER BY m.name");
                    break;
                case "POST /members":
                    DB.update("INSERT INTO members(name,email,phone) VALUES(?,?,?)", s(rq, "name"), s(rq, "email"), rq.getParameter("phone"));
                    out = ok(); break;
                case "GET /issues":
                    out = DB.query("SELECT i.id, b.title, m.name AS member, i.issue_date, i.due_date, i.return_date, " + FINE
                            + " AS fine, i.fine_paid FROM issues i JOIN books b ON b.id=i.book_id JOIN members m ON m.id=i.member_id"
                            + " ORDER BY (i.return_date IS NULL) DESC, i.due_date");
                    break;
                case "POST /issue":  out = issueBook(n(rq, "bookId"), n(rq, "memberId")); break;
                case "POST /return": out = returnBook(n(rq, "issueId")); break;
                case "POST /pay":    out = pay(n(rq, "issueId"), s(rq, "method"), rq.getParameter("ref")); break;
                case "GET /payments":
                    out = DB.query("SELECT p.receipt_no, p.amount, p.method, p.txn_ref, p.paid_at, m.name AS member, b.title FROM payments p"
                            + " JOIN issues i ON i.id=p.issue_id JOIN members m ON m.id=i.member_id JOIN books b ON b.id=i.book_id ORDER BY p.id DESC");
                    break;
                default: rs.setStatus(404); out = err("Not found");
            }
        } catch (IllegalStateException | NumberFormatException | java.sql.SQLIntegrityConstraintViolationException e) {
            rs.setStatus(400); out = err(e instanceof java.sql.SQLException ? "Duplicate or linked record exists" : e.getMessage());
        } catch (Exception e) {
            rs.setStatus(500); out = err(e.getMessage());
        }
        rs.getWriter().print(G.toJson(out));
    }

    // ---- business logic ----
    Object stats() throws Exception {
        Map<String, Object> c = DB.query("SELECT"
                + " (SELECT COALESCE(SUM(total),0) FROM books) AS books,"
                + " (SELECT COUNT(*) FROM members) AS members,"
                + " (SELECT COUNT(*) FROM issues WHERE return_date IS NULL) AS issued,"
                + " (SELECT COUNT(*) FROM issues WHERE return_date IS NULL AND due_date<CURDATE()) AS overdue,"
                + " (SELECT COALESCE(SUM(amount),0) FROM payments) AS collected,"
                + " (SELECT COALESCE(SUM(GREATEST(DATEDIFF(IFNULL(return_date,CURDATE()),due_date),0)*" + FINE_PER_DAY + "),0) FROM issues WHERE fine_paid=0) AS pending").get(0);
        List<Map<String, Object>> cats = DB.query("SELECT b.category AS name, COUNT(*) AS n FROM issues i JOIN books b ON b.id=i.book_id GROUP BY b.category ORDER BY n DESC LIMIT 5");
        return Map.of("c", c, "cats", cats);
    }

    Object issueBook(int bookId, int memberId) throws Exception {
        try (Connection c = DB.get()) {
            c.setAutoCommit(false);
            try {
                if (DB.update(c, "UPDATE books SET available=available-1 WHERE id=? AND available>0", bookId) == 0)
                    throw new IllegalStateException("No copies available");
                DB.update(c, "INSERT INTO issues(book_id,member_id,issue_date,due_date) VALUES(?,?,CURDATE(),DATE_ADD(CURDATE(),INTERVAL " + LOAN_DAYS + " DAY))", bookId, memberId);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
        return ok();
    }

    Object returnBook(int issueId) throws Exception {
        try (Connection c = DB.get()) {
            c.setAutoCommit(false);
            try {
                if (DB.update(c, "UPDATE issues SET return_date=CURDATE() WHERE id=? AND return_date IS NULL", issueId) == 0)
                    throw new IllegalStateException("Already returned");
                DB.update(c, "UPDATE books SET available=available+1 WHERE id=(SELECT book_id FROM issues WHERE id=?)", issueId);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
        return ok();
    }

    /** Fine payment: validates method, records payment + receipt, marks fine as paid (atomic). */
    Object pay(int issueId, String method, String ref) throws Exception {
        if (!METHODS.contains(method)) throw new IllegalStateException("Invalid payment method");
        List<Map<String, Object>> r = DB.query("SELECT " + FINE + " AS fine, i.fine_paid FROM issues i WHERE i.id=?", issueId);
        if (r.isEmpty()) throw new IllegalStateException("Loan not found");
        double fine = ((Number) r.get(0).get("fine")).doubleValue();
        if (fine <= 0) throw new IllegalStateException("No fine due");
        if (((Number) r.get(0).get("fine_paid")).intValue() == 1) throw new IllegalStateException("Fine already paid");
        String receipt = "RCPT-" + System.currentTimeMillis();
        try (Connection c = DB.get()) {
            c.setAutoCommit(false);
            try {
                DB.update(c, "INSERT INTO payments(issue_id,amount,method,txn_ref,receipt_no) VALUES(?,?,?,?,?)", issueId, fine, method, ref == null ? "" : ref, receipt);
                DB.update(c, "UPDATE issues SET fine_paid=1 WHERE id=?", issueId);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
        return Map.of("ok", true, "receipt", receipt, "amount", fine, "method", method);
    }

    // ---- helpers ----
    static Map<String, Object> ok()  { return Map.of("ok", true); }
    static Map<String, Object> err(String m) { return Map.of("error", m == null ? "Error" : m); }
    static String s(HttpServletRequest r, String k) {
        String v = r.getParameter(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalStateException("Missing field: " + k);
        return v.trim();
    }
    static int n(HttpServletRequest r, String k) { return Integer.parseInt(s(r, k)); }
}
