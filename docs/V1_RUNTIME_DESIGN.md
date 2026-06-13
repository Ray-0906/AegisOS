# AegisOS v1.0 Runtime Design

## Objective
Transition from the prototype ClassLoader-based JVM job execution model to a secure, language-agnostic OCI (Open Container Initiative) compliant container runtime for AegisOS v1.0.

## Background
Currently, AegisOS executes jobs by dynamically loading JAR artifacts via custom ClassLoaders within the `ProcessRuntimeAgent`. While this works for the Java-based prototype, it has several critical flaws:
1. **Security/Isolation:** Jobs run within the worker node's JVM, sharing memory and OS privileges. A malicious job can escape and access host files or cluster credentials.
2. **Language Lock-in:** Only JVM-compatible languages are supported.
3. **Dependency Hell:** Managing native libraries or conflicting JVM versions is impossible.

## Proposed Architecture (v1.0)

For v1.0, AegisOS will adopt **OCI Containers** as the fundamental execution unit.

### 1. Artifact Registry Evolution
* Currently, `aegis put` uploads flat files.
* In v1.0, the Artifact Registry will become an OCI-compliant registry (or integrate with one).
* Users will build Docker/OCI images (`docker build -t myjob:1.0 .`) and push them to the AegisOS cluster (`aegis push myjob:1.0`).
* AegisFS will store the container image layers.

### 2. Job Submission & Scheduling
* `aegis run --image myjob:1.0` will submit an OCI job.
* The `Scheduler` will select a worker node based on available CPU/Memory resources.
* `ClusterStateMachine` will record the job allocation.

### 3. Worker Node Execution (`ContainerRuntimeAgent`)
* The current `ProcessRuntimeAgent` will be replaced or augmented by a `ContainerRuntimeAgent`.
* Instead of ClassLoaders, the agent will interface with a low-level container runtime (e.g., `containerd`, `runc`, or `crun`).
* **Flow:**
  1. Pull image layers from AegisFS to local disk.
  2. Unpack layers into a rootfs bundle.
  3. Generate an OCI `config.json` defining resource limits (cgroups), namespaces (isolation), and entrypoint.
  4. Spawn the container via `runc`.
  5. Stream `stdout`/`stderr` back to the distributed `JobRegistry`.

### 4. Job APIs & State Communication
* In the prototype, jobs call `AegisContext.reportProgress()`.
* In v1.0, this will transition to an HTTP-based local metadata server (similar to AWS metadata service) or a bound Unix Domain Socket.
* The container will `POST http://169.254.169.254/v1/progress` to report progress to the local Aegis agent, which forwards it to the cluster via Raft.

## Security Posture
* **cgroups v2:** Used to strictly enforce memory and CPU limits.
* **Namespaces:** Network isolation (no outbound internet by default, only internal service mesh), PID isolation, Mount isolation.
* **Seccomp:** Restrict dangerous syscalls.
* **Rootless Containers:** Containers should run as non-root users on the host machine.

## Migration Path
1. Implement the `AegisFS -> rootfs` unpacker.
2. Integrate a Java library for `runc` execution (e.g., NuProcess or standard ProcessBuilder wrapping `runc start`).
3. Add OCI image support to the CLI (`aegis build`, `aegis push`).
4. Deprecate the ClassLoader executor.
