import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
out=Path(__file__).resolve().parent/'evidence/20261005';r=json.loads((out/'audit.json').read_text('utf8'))['timing'];names=['cancel-stalled','cancel-dripping','deadline-stalled','deadline-dripping'];fig,axes=plt.subplots(1,2,figsize=(15,6),dpi=100)
fig.set_facecolor('#faf9f6')
for ax,policy,color in zip(axes,['interrupt-only','close-socket'],['#b78061','#56836d']):
 g=[next(x for x in r if x['condition']==n and x['policy']==policy) for n in names];y=np.arange(4);v=[x['median_ms'] for x in g]
 ax.set_facecolor('#faf9f6');ax.barh(y,v,color=color);ax.errorbar(v,y,xerr=[[x['median_ms']-x['min_ms'] for x in g],[x['max_ms']-x['median_ms'] for x in g]],fmt='none',color='#444',capsize=4)
 ax.set_yticks(y,names);ax.set_xlabel('Action to actual reader exit (ms); median and range, n=3');ax.set_title(policy+' (independent x scale)');ax.spines[['top','right']].set_visible(False)
 for i,x in enumerate(v):ax.text(x,i,f'  {x:.3f}',va='center')
 ax.set_xlim(0,max(x['max_ms'] for x in g)*1.25)
fig.suptitle('A8 / Cancelling a Future is not the same as terminating a socket read',fontsize=17);fig.tight_layout();fig.savefig(out/'transport-cancellation.png');plt.close(fig)
