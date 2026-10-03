import io, sys

s = sys.argv[1]
pw = sys.argv[2]
window = sys.argv[3] if len(sys.argv) > 3 else 'W2'
action = sys.argv[4] if len(sys.argv) > 4 else 'run the probe tool'

cp = open('D:/AI-project/Ruoyi-Ai-AgentScope/services/ai/agent/target/probe-cp.txt').read().replace('\\', '/').strip()
lines = [
    '-cp "%s;."' % cp,
    'Probe34a',
    '--window=%s' % window,
    '--crash-save-count=1',
    '--session=%s' % s,
    '--action=%s' % action,
    '--jdbc=jdbc:postgresql://127.0.0.1:25433/ragent_p2core?sslmode=disable',
    '--db-user=p2app',
    '--db-pass=%s' % pw,
]
io.open('D:/AI-project/Ruoyi-Ai-AgentScope/tools/p3-34a/probe/java-args.txt', 'w', newline='\n').write('\n'.join(lines) + '\n')
print('args ready', s, window)
