import json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
out=Path(__file__).resolve().parent/'evidence/20261004';rows=json.loads((out/'audit.json').read_text('utf8'))['totals'];r=[x for x in rows if x['seed']==7]
fig,axes=plt.subplots(1,2,figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6');names=['hot-prefix','alternating-branches','three-scopes'];y=np.arange(3)
for ax in axes:ax.set_facecolor('#faf9f6');ax.spines[['top','right']].set_visible(False);ax.set_xticks(y,names)
for j,(budget,color) in enumerate(zip((4096,8192,32768),('#56836d','#b78061','#899ab4'))):
 rows=[next(x for x in r if x['budget']==budget and x['trace']==n) for n in names]
 axes[0].bar(y+(j-1)*.24,[x['projected_rows'] for x in rows],width=.23,color=color,label=f'{budget//1024} KiB')
 axes[1].bar(y+(j-1)*.24,[x['evictions'] for x in rows],width=.23,color=color,label=f'{budget//1024} KiB')
axes[0].axhline(864,ls=':',color='#555',label='Fresh: 864');axes[0].set_ylabel('Q/K/V rows, all 12 requests (cold start included)');axes[1].set_ylabel('Checkpoint evictions');axes[0].legend(frameon=False);axes[1].legend(frameon=False)
fig.suptitle('L7 / A larger cache can churn more without saving any projection work',fontsize=17);fig.tight_layout();fig.savefig(out/'cache-budget.png');plt.close(fig)
