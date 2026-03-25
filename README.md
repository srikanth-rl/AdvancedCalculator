# CalculatorApp — Java Servlet Backend

A direct port of the original JS calculator to a **Java + Jakarta Servlet** backend,
keeping the identical HTML/CSS frontend structure.

---

## Project Structure

```
CalculatorApp/
├── pom.xml                              ← Maven build (Java 17, Tomcat 10+)
└── src/main/
    ├── java/com/calculator/servlet/
    │   ├── CalculatorServlet.java        ← POST /calculate  (add/sub/mul/div/mod/factorial/prime/percent)
    │   ├── ExpressionServlet.java        ← POST /evaluate   (full infix expression evaluator)
    │   └── HistoryServlet.java           ← GET|POST /history (session-based history)
    └── webapp/
        ├── index.html                   ← Same layout as original
        ├── WEB-INF/web.xml
        └── static/
            ├── css/style.css            ← Same styles as original + dark-mode body overrides
            ├── js/functions.js          ← All logic now calls Java servlets via fetch()
            └── download.png             ← Favicon

```

---

## API Endpoints

### `POST /evaluate`
Evaluates a full infix expression (used by the `=` button).

| Param        | Example         |
|--------------|-----------------|
| `expression` | `5+3*2-1`       |

Returns `{ "success": true, "result": "10" }`

---

### `POST /calculate`
Handles individual operations.

| `action`    | Required params          | Notes                              |
|-------------|--------------------------|-------------------------------------|
| `add`       | `num1`, `num2`           |                                     |
| `subtract`  | `num1`, `num2`           |                                     |
| `multiply`  | `num1`, `num2`           |                                     |
| `divide`    | `num1`, `num2`           | Throws on divide-by-zero            |
| `mod`       | `num1`, `num2`           | True mathematical mod (BigInteger)  |
| `factorial` | `num1`                   | Up to 5-digit input; uses BigInteger|
| `prime`     | `num1`                   | Up to 17-digit input                |
| `percent`   | `num1`                   | Returns num1 / 100                  |

Returns `{ "success": true, "result": "..." }` or `{ "success": false, "error": "..." }`

---

### `GET /history`
Returns session history as JSON array.

### `POST /history`
- Add entry: params `expression`, `result`
- Clear all: param `action=clear`

---

## Build & Deploy

### Prerequisites
- Java 17+
- Maven 3.8+
- Apache Tomcat 10.x (Jakarta EE 10)

### Build
```bash
cd CalculatorApp
mvn clean package
```
This produces `target/CalculatorApp.war`.

### Deploy to Tomcat
```bash
cp target/CalculatorApp.war $TOMCAT_HOME/webapps/
# or deploy to ROOT for no context path:
cp target/CalculatorApp.war $TOMCAT_HOME/webapps/ROOT.war
```

Then open: `http://localhost:8080/CalculatorApp/`  
(or `http://localhost:8080/` if deployed as ROOT)

---

## Key Design Decisions

| Feature          | Original (JS)                  | This project (Java)                            |
|------------------|--------------------------------|------------------------------------------------|
| Arithmetic eval  | `eval(expression)`             | `ExpressionServlet` — shunting-yard algorithm  |
| BigInt math      | JS `BigInt`                    | `java.math.BigInteger` + `BigDecimal`          |
| Factorial        | JS BigInt loop                 | Java BigInteger loop, result length included   |
| Prime check      | JS naive trial division        | Java BigInteger trial division (same algorithm)|
| Mod              | JS `BigInt % BigInt`           | Java `BigInteger.mod()` (true mathematical mod)|
| History          | JS in-memory `history[]`       | Server-side `HttpSession` attribute            |
| Dark mode        | CSS class toggle               | Same — pure CSS class toggle                   |
