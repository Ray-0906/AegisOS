const fs = require('fs');

let expected = 1;
const chk = (process.env.AEGIS_CHECKPOINT_DIR || '.') + '/checkpoint.dat';

if (fs.existsSync(chk)) {
    const content = fs.readFileSync(chk, 'utf8').trim();
    if (content) {
        expected = parseInt(content, 10);
    }
}

process.stdin.on('data', (data) => {
    const lines = data.toString().split('\n').filter(line => line.trim() !== '');
    for (const line of lines) {
        if (line.startsWith('SEQ_')) {
            const num = parseInt(line.split('_'), 10);
            if (num !== expected) {
                console.error(`CORRUPTION DETECTED: Expected SEQ_${expected}, received ${line}`);
                process.exit(1);
            }
            expected = num + 1;
            fs.writeFileSync(chk, expected.toString());
        }
    }
});
