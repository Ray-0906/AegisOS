import subprocess
import re

runs = 50

for i in range(runs):
    print(f"Run {i+1}/{runs}...")
    cmd = ["mvn.cmd", "test", "-pl", "aegis-test-cluster", "-Dtest=LeaderFailoverCheckpointTest"]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=r"c:\Users\astra\Desktop\AegisOS")
    
    if result.returncode != 0:
        print(f"Run {i+1} FAILED!")
        with open(f"fail_pr2_{i}.log", "w") as f:
            f.write(result.stdout)
        break
        
    lines = result.stdout.split('\n')
    leader_elected = next((l.split('=')[1] for l in lines if 'leader_elected_ms=' in l), "N/A")
    checkpoint_visible = next((l.split('=')[1] for l in lines if 'checkpoint_visible_ms=' in l), "N/A")
    checkpoint_restored = next((l.split('=')[1] for l in lines if 'checkpoint_restored_ms=' in l), "N/A")
    gossip_dead = next((l.split('=')[1] for l in lines if 'gossip_dead_ms=' in l), "N/A")
    
    print(f"  leader_elected={leader_elected}ms, checkpoint_visible={checkpoint_visible}ms, checkpoint_restored={checkpoint_restored}ms, gossip_dead={gossip_dead}ms")

print("Instrumentation complete.")
