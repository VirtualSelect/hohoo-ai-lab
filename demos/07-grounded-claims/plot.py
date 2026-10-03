import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
HERE=Path(__file__).resolve().parent
out=HERE/'evidence/20261003';data=json.loads((out/'results.json').read_text('utf8'))['cases']
values=[len(data),sum(r['actual']!='INVALID_JSON' for r in data),sum(r['citationOnly'] for r in data),sum(r['actual']=='ACCEPT' for r in data)]
fig,ax=plt.subplots(figsize=(15,6),dpi=100);fig.set_facecolor('#faf9f6');ax.set_facecolor('#faf9f6')
bars=ax.barh(['Submitted','Strict JSON','Quote exists','Full contract'],values,color=['#89969c','#8ba2b4','#bc9073','#4f806c'])
ax.bar_label(bars,padding=7,fontsize=14);ax.invert_yaxis();ax.set_xlim(0,25)
ax.set_xlabel('Cases in the 22-item synthetic fixture set');ax.set_title('A5 / Citation existence is only one admission check',fontsize=18,pad=18)
ax.text(12,2.3,'10 cited cases still violate the contract',fontsize=13,color='#845138')
ax.spines[['top','right']].set_visible(False);fig.tight_layout();fig.savefig(out/'claim-contract.png');plt.close(fig)
