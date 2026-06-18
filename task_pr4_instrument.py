import subprocess
import re

runs = 50

for i in range(runs):
    print(f"Run {i+1}/{runs}...")
    cmd = ["mvn.cmd", "test", "-pl", "aegis-test-cluster", "-Dtest=LeaderFailoverDuringRecoveryTest"]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=r"c:\Users\astra\Desktop\AegisOS")
    
    if result.returncode != 0:
        print(f"Run {i+1} FAILED!")
        with open(f"fail_pr4_{i}.log", "w") as f:
            f.write(result.stdout)
        break
        
    lines = result.stdout.split('\n')
    leader_elected = next((l.split('=')[1] for l in lines if 'leader_elected_ms=' in l), "N/A")
    recovery_visible = next((l.split('=')[1] for l in lines if 'recovery_visible_ms=' in l), "N/A")
    job_recovered = next((l.split('=')[1] for l in lines if 'job_recovered_ms=' in l), "N/A")
    gossip_dead = next((l.split('=')[1] for l in lines if 'gossip_dead_ms=' in l), "N/A")
    
    print(f"  leader_elected={leader_elected}ms, recovery_visible={recovery_visible}ms, job_recovered={job_recovered}ms, gossip_dead={gossip_dead}ms")

print("Instrumentation complete.")
