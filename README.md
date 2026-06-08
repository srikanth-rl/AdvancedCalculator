# 🧮 Calculator App

A powerful arbitrary-precision calculator web application built with Java Servlets and vanilla JavaScript. 
Supports massive numbers on any operations — factorials up to 3 million, prime checks up to 7,000 digits, and other expressions also supported using scientific notation.

---

## ✨ Features

- **Basic Arithmetic** — Addition, Subtraction, Multiplication, Division, Percentage
- **Power** — Use `**` syntax (e.g. `5**3 = 125`)
- **Factorial** — Supports any number up to 3,000,000 (3 Millions)
- **Modulo (Remainder)** — Two-input remainder operation
- **GCD** — Greatest Common Divisor of two numbers
- **LCM** — Least Common Multiple of two numbers
- **Prime Check** — Supports any length up to 7,000 digits
- **Scientific Notation** — `1e9`, `1.5e+10`, `2E-3` all supported
- **Calculation History** — Persisted per session, viewable in sidebar
- **Copy Results** — Copy any result from history with one click
- **Download Results** — Download full result as `.txt` for large outputs (1,000+ digits)
- **Dark / Light Mode** — Toggle anytime; dark mode default on mobile
- **Clock** — Live clock with date displayed on desktop
- **Keyboard Shortcuts** — Full keyboard support for all operations
- **Responsive** — Works on desktop, tablet, and mobile

---

## 📋 History

- Stores up to **100 entries** per session
- Results longer than **5,000 characters** are truncated in storage with a message: `… [remaining digits hidden — use desktop to copy full result] …`
- Full results available for copy on desktop via in-memory cache
- History is cleared when the user clicks **Clear History** or the session expires (default: 30 minutes)

---

## ⌨️ Keyboard Shortcuts

| Key                 | Action                                |
|---------------------|---------------------------------------|
| `Enter`             | Calculate (`=`)                       |
| `Backspace`         | Delete cursor pointed / last character|
| `Ctrl + Backspace`  | Clear input                           |
| `F`                 | Factorial                             |
| `G`                 | GCD                                   |
| `L`                 | LCM                                   |
| `M`                 | Mod (Remainder)                       |
| `P`                 | Prime Check                           |
| `H`                 | View History                          |

---

## 🔧 Tech Stack

| Layer     | Technology                            |
|-----------|---------------------------------------|
| Backend   | Java 25, Jakarta Servlet 6.1          |
| Compute   | Virtual Threads (`Thread.ofVirtual()`)|
| Math      | `BigInteger` / `BigDecimal`           |
| Frontend  | HTML, CSS, JavaScript                 |
| Server    | Apache Tomcat 11+                     |
| Deploy    | Oracle Cloud                          |

---

## 🚀 Setup & Deployment

### Prerequisites
- Java 21+
- Apache Tomcat 10+
- Maven (optional)

### Build & Deploy
```bash
# Package as WAR
mvn clean package

# Copy to Tomcat
cp target/ROOT.war $TOMCAT_HOME/webapps/

# Start Tomcat
$TOMCAT_HOME/bin/startup.sh
```

Then open: `http://localhost:8080/`

### 🐳 Docker (Quick Start)
```bash
# 1. Install OS-Specific Docker Engine
# https://docs.docker.com/engine/install/

# 2. Pull and run your app
docker run -d -p 8080:8080 --name calculator srikanthrl2003/calculator-app:latest

# 3. Open browser
http://localhost:8080
```
The image is multi-platform — works on both `linux/amd64` (most laptops) and `linux/arm64` (Oracle Cloud Ampere A1) for fast local testing and seamless cloud deployment.
 
---
 
### ☸️ Kubernetes (Minikube)
 
Run the app locally with auto-scaling using the provided manifests.
 
```bash
# 1. Start minikube
minikube start --driver=docker
 
# 2. Enable metrics-server (required for HPA)
minikube addons enable metrics-server
 
# 3. Apply all manifests
kubectl apply -f kubernetes/
 
# 4. Watch pods come up
kubectl get pods -w
 
# 5. Get the app URL
minikube service calculator-service --url
```
 
**What gets deployed:**
 
| Resource | Config |
|----------|--------|
| Deployment | 2 pods, `imagePullPolicy: Always` |
| Service | NodePort `:30080`, SessionAffinity: ClientIP (30 min) |
| HPA | CPU target 70%, min 1 pod → max 3 pods |
 
> **SessionAffinity** ensures the same user always hits the same pod, so calculation history is preserved when multiple pods are running.
 
> **For Oracle OKE (production):** change `type: NodePort` to `type: LoadBalancer` in `service.yaml` — the cloud assigns an external IP automatically.
 
---
 
### ⚙️ CI/CD Pipeline (GitHub Actions)
 
Every push to `main` automatically:
1. Builds a multi-platform Docker image (`amd64` + `arm64`)
2. Pushes two tags to Docker Hub:
   - `latest` — used by the OCI VM on every restart
   - `<commit-sha>` — immutable snapshot for safe rollbacks
```
git push origin main
       ↓
 GitHub Actions builds image
       ↓
 Pushes to Docker Hub
       ↓
 OCI VM pulls latest on next deploy
```
 
**Required GitHub Secrets** (`Settings → Secrets → Actions`):
 
| Secret | Value |
|--------|-------|
| `DOCKER_USERNAME` | your Docker Hub username |
| `DOCKER_TOKEN` | Docker Hub access token (Read & Write) |
 
---

## 👨‍💻 Creator

For feedback or issues → <a href="https://srikanthr.in/#contact" target="_blank" rel="noopener noreferrer">Contact</a>
