# 📚 LibraVerse – Library Management System

Java Servlets + **JDBC** + MySQL back end, single-page HTML/CSS/JS front end.

## Features
- Books (add / search / delete) with auto-generated colour covers
- Members, issue & return with **transaction-safe** stock updates
- Auto fine calculation (₹5/day after the 14-day loan period)
- **Payment module** – pay fines by Cash, UPI, Card, NetBanking or Wallet; receipt numbers, printable receipt, payment history
- Dashboard with live stats and "most borrowed categories" chart, dark mode

## Structure
```
pom.xml
database/schema.sql
src/main/java/com/library/DB.java          (JDBC helper)
src/main/java/com/library/ApiServlet.java  (REST-style API)
src/main/webapp/index.html                 (UI)
```

## Run
1. Install JDK 11+, Maven, MySQL 8, Tomcat 9 (for Tomcat 10+ replace `javax.servlet` with `jakarta.servlet`).
2. `mysql -u root -p < database/schema.sql`
3. Set DB credentials if needed: env vars `DB_URL`, `DB_USER`, `DB_PASS` (defaults: root / root).
4. `mvn clean package` → copy `target/library.war` to Tomcat's `webapps/`.
5. Open http://localhost:8080/library/
