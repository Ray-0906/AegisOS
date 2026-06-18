import subprocess
import re

runs = 50

for i in range(runs):
    print(f"Run {i+1}/{runs}...")
    cmd = ["mvn.cmd", "test", "-pl", "aegis-test-cluster", "-Dtest=ArtifactRestartRecoveryTest"]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=r"c:\Users\astra\Desktop\AegisOS")
    
    if result.returncode != 0:
        print(f"Run {i+1} FAILED!")
        with open(f"fail_pr3_{i}.log", "w") as f:
            f.write(result.stdout)
        break
        
    lines = result.stdout.split('\n')
    node_rejoins = next((l.split('=')[1] for l in lines if 'node_rejoins_ms=' in l), "N/A")
    artifact_visible = next((l.split('=')[1] for l in lines if 'artifact_visible_ms=' in l), "N/A")
    artifact_readable = next((l.split('=')[1] for l in lines if 'artifact_readable_ms=' in l), "N/A")
    gossip_stable = next((l.split('=')[1] for l in lines if 'gossip_stable_ms=' in l), "N/A")
    
    print(f"  node_rejoins={node_rejoins}ms, artifact_visible={artifact_visible}ms, artifact_readable={artifact_readable}ms, gossip_stable={gossip_stable}ms")

print("Instrumentation complete.")
