const readline = require('readline');

let currentLevel = 1;
let currentXp = 0;
let currentRank = 'E-RANK';

function getRank(level) {
    if (level >= 50) return 'S-RANK';
    if (level >= 41) return 'A-RANK';
    if (level >= 31) return 'B-RANK';
    if (level >= 21) return 'C-RANK';
    if (level >= 11) return 'D-RANK';
    return 'E-RANK';
}

function calculateRequiredXp(level) {
    return Math.floor(500 * Math.pow(1.2, level));
}

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
});

rl.on('line', (line) => {
    if (!line.trim()) return;
    
    try {
        const event = JSON.parse(line);
        if (!event.xp_yield) return;
        
        currentXp += event.xp_yield;
        let leveledUp = false;
        
        while (currentXp >= calculateRequiredXp(currentLevel)) {
            currentXp -= calculateRequiredXp(currentLevel);
            currentLevel++;
            leveledUp = true;
        }
        
        if (leveledUp) {
            const newRank = getRank(currentLevel);
            let rankMessage = "";
            if (newRank !== currentRank) {
                rankMessage = ` Hunter Rank Re-evaluated: ${newRank}.`;
                currentRank = newRank;
            }
            console.log(`[SYSTEM NOTIFICATION] Level Up! Current Level: ${currentLevel}.${rankMessage}`);
        }
    } catch (e) {
    }
});
