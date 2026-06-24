import sys
import json
import time
import random
import uuid

GRADES = {"E": 50, "D": 150, "C": 500, "B": 1200, "A": 3000, "S": 10000}
CLASSES = ["magic_beast", "gate", "shadow_soldier", "dungeon_boss"]
ACTIONS = ["monster_kill", "dungeon_clear", "resource_extraction"]

def generate_event():
    grade = random.choices(list(GRADES.keys()), weights=[100, 50, 20, 10, 5, 1])[0]
    base_xp = GRADES[grade]
    xp_yield = int(base_xp * random.uniform(0.8, 1.5))
    
    return {
        "event_id": f"evt_{uuid.uuid4().hex[:8]}",
        "action_type": random.choice(ACTIONS),
        "entity_class": random.choice(CLASSES),
        "grade": grade,
        "xp_yield": xp_yield
    }

def run():
    while True:
        event = generate_event()
        sys.stdout.write(json.dumps(event) + "\n")
        sys.stdout.flush()
        time.sleep(random.uniform(0.1, 0.5))

if __name__ == "__main__":
    run()
