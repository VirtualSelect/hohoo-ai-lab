import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
out=Path(__file__).resolve().parent/'evidence/20261003-r2';r=json.loads((out/'results.json').read_text(encoding='utf8'))['cases']
fig,axes=plt.subplots(1,2,figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6')
for ax,rows in zip(axes,[r[:11],r[11:]]):
 ax.set_facecolor('#faf9f6');ax.barh(range(len(rows)),[len(x['preview']) for x in rows],color=['#56836d' if x['committed'] else '#b78061' for x in rows]);ax.set_yticks(range(len(rows)),[x['case'] for x in rows]);ax.invert_yaxis();ax.set_xlim(0,12);ax.set_xlabel('Visible preview (Unicode code points)');ax.spines[['top','right']].set_visible(False)
 for i,x in enumerate(rows):ax.text(len(x['preview'])+.15,i,'COMMIT' if x['committed'] else 'NO COMMIT',va='center',fontsize=9)
fig.suptitle('A6 / Preview can exist even when the conversation is not committed',fontsize=17);fig.tight_layout();fig.savefig(out/'streaming-boundary.png');plt.close(fig)
