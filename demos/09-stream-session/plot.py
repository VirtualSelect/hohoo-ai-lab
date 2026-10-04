import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
out=Path(__file__).resolve().parent/'evidence/20261004';r=json.loads((out/'results.json').read_text('utf8'))['cases'];bad=json.loads((out/'audit.json').read_text('utf8'))['failed_controls'];names=list(dict.fromkeys(x['case'] for x in r))
fig,ax=plt.subplots(figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6');ax.set_facecolor('#faf9f6')
for i,n in enumerate(names):
 for x,policy in enumerate(('naive','owned')):
  failed=policy=='naive' and n in bad;ax.scatter(x,i,s=900,marker='s',color='#b78061' if failed else '#56836d');ax.text(x,i,'FAIL' if failed else 'PASS',ha='center',va='center',color='white',fontsize=10)
ax.set_xticks([0,1],['Any callback may write','Only the active request may write']);ax.set_yticks(range(len(names)),names);ax.invert_yaxis();ax.set_xlim(-.6,1.6);ax.spines[['top','right','bottom','left']].set_visible(False);ax.tick_params(length=0)
fig.suptitle('A7 / 18 deterministic two-thread schedules: preview ownership matters',fontsize=17);fig.tight_layout();fig.savefig(out/'stream-session.png');plt.close(fig)
