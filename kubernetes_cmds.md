# Kubernetes Cheatsheet — Interview + CalculatorApp Local Testing

---

## 1. Minikube — Local Cluster Setup

```bash
minikube start                        # start local k8s cluster
minikube start --driver=docker        # use docker as driver (recommended)
minikube status                       # check cluster health
minikube stop                         # stop cluster
minikube delete                       # wipe cluster completely
minikube dashboard                    # open k8s web UI in browser
```

---

## 2. Apply Your Manifests

```bash
kubectl apply -f kubernetes/          # apply all files in folder
kubectl apply -f deployment.yaml      # apply specific file
kubectl delete -f kubernetes/         # tear everything down
```

---

## 3. Pods

```bash
kubectl get pods                      # list all pods + status
kubectl get pods -w                   # watch live (like tail)
kubectl describe pod <pod-name>       # full details — events, errors
kubectl logs <pod-name>               # stdout logs (Tomcat logs here)
kubectl logs <pod-name> -f            # follow logs live
kubectl exec -it <pod-name> -- bash   # shell into running pod
kubectl delete pod <pod-name>         # force restart (k8s recreates it)
```

---

## 4. Deployment

```bash
kubectl get deployments                          # list deployments
kubectl describe deployment calculator-app       # full spec + events
kubectl rollout status deployment/calculator-app # watch rollout progress
kubectl rollout history deployment/calculator-app # see revision history

# Force re-pull latest image (useful after docker push)
kubectl rollout restart deployment/calculator-app

# Rollback to previous version
kubectl rollout undo deployment/calculator-app
```

---

## 5. Service

```bash
kubectl get svc                        # list services
kubectl describe svc calculator-service

# Get the URL to access app in minikube
minikube service calculator-service --url
# OR open directly in browser
minikube service calculator-service
```

> Your service uses `NodePort: 30080` — so URL will be  
> `http://<minikube-ip>:30080`  
> Get minikube IP with: `minikube ip`

---

## 6. HPA — Horizontal Pod Autoscaler

```bash
kubectl get hpa                        # list HPAs + current/target CPU
kubectl describe hpa calculator-hpa    # detailed scaling events
```

> **Note:** HPA needs metrics-server to work locally.  
> Enable it with: `minikube addons enable metrics-server`

---

## 7. Debugging Flow (when pod won't start)

```bash
kubectl get pods                        # check STATUS — CrashLoopBackOff? Pending?
kubectl describe pod <pod-name>         # check Events section at bottom
kubectl logs <pod-name>                 # check app errors
kubectl logs <pod-name> --previous      # logs from previous crashed container
```

Common issues:
- `ImagePullBackOff` → Docker Hub image name wrong, or image not pushed yet
- `CrashLoopBackOff` → app is crashing inside container, check logs
- `Pending` → not enough resources, check `kubectl describe pod` events

---

## 8. Resource Inspection

```bash
kubectl get all                         # pods + services + deployments + hpa at once
kubectl get events --sort-by='.lastTimestamp'   # recent cluster events
kubectl top pods                        # CPU/memory per pod (needs metrics-server)
kubectl top nodes                       # node resource usage
```

---

## 9. ConfigMap & Secrets (common in interviews)

```bash
kubectl create configmap my-config --from-literal=KEY=VALUE
kubectl create secret generic my-secret --from-literal=PASSWORD=secret123
kubectl get configmaps
kubectl get secrets
```

---

## 10. Namespaces

```bash
kubectl get namespaces
kubectl get pods -n kube-system         # pods in system namespace
kubectl get pods --all-namespaces       # pods across all namespaces
```

---

## 11. CalculatorApp — Full Local Test Flow

```bash
# 1. Start minikube
minikube start --driver=docker

# 2. Enable metrics-server (for HPA)
minikube addons enable metrics-server

# 3. Apply manifests
kubectl apply -f kubernetes/

# 4. Watch pods come up
kubectl get pods -w

# 5. Get app URL
minikube service calculator-service --url

# 6. Check HPA is watching
kubectl get hpa

# 7. Check logs
kubectl logs -l app=calculator          # logs from all calculator pods
```

---

## 12. Key Interview Concepts from Your Setup

| Concept | Your Config | Why |
|---|---|---|
| **Replicas** | 2 | handles concurrent heavy math requests |
| **HPA** | min 1, max 3, CPU 70% | auto scales on factorial/prime load |
| **NodePort** | 30080 | local testing; switch to LoadBalancer on OKE |
| **SessionAffinity** | ClientIP, 1800s | same user → same pod → history preserved |
| **imagePullPolicy: Always** | Always | ensures latest Docker Hub image on every restart |
| **Multi-platform image** | amd64 + arm64 | runs on GitHub runner (amd64) + OCI VM (arm64) |

---

## 13. minikube vs Production (OKE) difference

| | minikube (local) | OKE (Oracle Cloud) |
|---|---|---|
| Service type | NodePort | LoadBalancer |
| Access URL | `minikube ip`:30080 | Cloud-assigned external IP |
| Image pull | from Docker Hub | from Docker Hub or OCIR |
| HPA | needs `metrics-server` addon | built-in |
