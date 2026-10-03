import json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
out=Path(__file__).resolve().parent/'evidence/20261003';r=json.loads((out/'results.json').read_text());names=list(dict.fromkeys(x['condition'] for x in r))
fig,axes=plt.subplots(1,2,figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6')
for ax in axes:ax.set_facecolor('#faf9f6');ax.spines[['top','right']].set_visible(False)
y=np.arange(len(names));first=r[:9];axes[0].barh(y,[x['fresh_projected_rows'] for x in first],color='#d8d5cd',label='Full query');axes[0].barh(y,[x['projected_rows'] for x in first],color='#56836d',height=.45,label='After lookup');axes[0].set_yticks(y,names);axes[0].invert_yaxis();axes[0].set_xlabel('Q / K / V projected rows (not elapsed time)');axes[0].legend(frameon=False)
for seed,col in zip([7,11,23],['#56836d','#b78061','#899ab4']):
 rows=[x for x in r if x['seed']==seed and x['condition'] in ('edit-middle','edit-last','shorter')];axes[1].plot([x['condition'] for x in rows],[x['wrong_error'] for x in rows],'o-',label=f'seed {seed}',color=col)
axes[1].set_ylabel('Max absolute error, stale next_position');axes[1].legend(frameon=False);axes[1].set_title('Correct reuse: all 27 errors <= 3.9e-16')
fig.suptitle('L6 / Reuse the identical prefix; reset the continuation position to the cut',fontsize=17);fig.tight_layout();fig.savefig(out/'longest-prefix-reuse.png');plt.close(fig)
