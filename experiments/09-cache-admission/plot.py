import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
out=Path(__file__).resolve().parent/'evidence/20261005';r=json.loads((out/'audit.json').read_text('utf8'))['summary'];names=['hot-prefix','alternating-branches','three-scopes'];fig,axes=plt.subplots(1,2,figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6');x=np.arange(3)
for ax in axes:ax.set_facecolor('#faf9f6');ax.set_xticks(x,names);ax.spines[['top','right']].set_visible(False)
for i,(p,c) in enumerate(zip(['none','all','longest'],['#899ab4','#b78061','#56836d'])):
 g=[next(v for v in r if v['budget']==16384 and v['trace']==n and v['policy']==p) for n in names]
 axes[0].bar(x+(i-1)*.24,[v['projected_rows'] for v in g],width=.23,color=c,label=p)
 axes[1].bar(x+(i-1)*.24,[v['median_ms'] for v in g],width=.23,color=c,label=p)
axes[0].set_ylabel('Projected Q/K/V rows / 12 requests');axes[1].set_ylabel('Query time / 12 requests (ms, median of 15 traces)')
for ax in axes:ax.legend(frameon=False)
fig.suptitle('L8 / 16 KiB: less computation, but not a faster tiny NumPy query',fontsize=17);fig.tight_layout();fig.savefig(out/'cache-admission.png');plt.close(fig)
