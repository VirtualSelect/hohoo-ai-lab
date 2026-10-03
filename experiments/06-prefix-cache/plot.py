import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
HERE=Path(__file__).resolve().parent;out=HERE/'evidence/20261003'
rows=json.loads((out/'results.json').read_text('utf8'));cases=['edited-prefix','different-weights','different-window']
fig,ax=plt.subplots(figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6');ax.set_facecolor('#faf9f6')
for j,(seed,color) in enumerate(zip([7,19,41],['#54836e','#7d8fab','#b68e70'])):
 values=[next(r['unchecked_error'] for r in rows if r['seed']==seed and r['case']==c) for c in cases]
 bars=ax.bar(np.arange(3)+(j-1)*.24,values,.23,label=f'Seed {seed}',color=color);ax.bar_label(bars,fmt='%.4f',fontsize=12,padding=4)
ax.set_xticks(np.arange(3),['Prefix edited','Weights changed','Window changed']);ax.set_ylabel('Max absolute suffix-state error')
ax.set_ylim(0,.16);ax.set_title('L5 / Unchecked old-cache reuse versus fresh computation',fontsize=18,pad=15)
ax.text(.01,.96,'Guarded path: 0 measured error in all 18 cases (float64 teaching network)',transform=ax.transAxes,va='top',fontsize=12)
ax.legend(frameon=False);ax.spines[['top','right']].set_visible(False);fig.tight_layout();fig.savefig(out/'prefix-cache-invalid.png');plt.close(fig)
