from pathlib import Path
import sys
att=Path(sys.argv[1]);figs=att/'figures';figs.mkdir(exist_ok=True)
import numpy as np,matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
a=np.load(att/'arrays.npz',allow_pickle=False)
plt.rcParams.update({'figure.facecolor':'#faf9f5','axes.facecolor':'#faf9f5','axes.spines.top':False,'axes.spines.right':False,'font.size':10})
fig,axes=plt.subplots(1,2,figsize=(10,4),layout='constrained')
im=axes[0].imshow(a['s7_attention'],cmap='Blues',vmin=0,vmax=1);axes[0].set(xlabel='Key position',ylabel='Query position',title='Causal attention / layer 1, seed 7');fig.colorbar(im,ax=axes[0])
diff=np.max(np.abs(a['s7_unmasked']-a['s7_perturbed_unmasked']),axis=1);axes[1].plot(diff,color='#aa7252',label='No mask');axes[1].plot(np.max(np.abs(a['s7_full']-a['s7_masked']),axis=1),color='#54816c',label='Causal');axes[1].axvline(11.5,ls=':',color='#777');axes[1].set(xlabel='Token position',ylabel='Max coordinate change',title='Only tokens 12..23 changed');axes[1].legend();fig.savefig(figs/'causal-mask.png',dpi=150);plt.close(fig)
fig,ax=plt.subplots(figsize=(10,4),layout='constrained');s=a['s7_cache_bytes'];ax.plot(s[:,0],s[:,1],color='#54816c',label='Full K/V payload');ax.plot(s[:,0],np.minimum(s[:,0],4)*2*2*16*8,color='#537d9f',label='Window 4 K/V payload');ax.set(xlabel='Tokens processed',ylabel='Array payload (bytes)',title='Same float64 implementation; excludes metadata and allocator overhead');ax.legend();fig.savefig(figs/'kv-storage.png',dpi=150);plt.close(fig)
fig,ax=plt.subplots(figsize=(10,4),layout='constrained')
for label,key,color in [('Reset positions','reset','#aa7252'),('Recompute cropped tokens','cropped','#86577e'),('Sliding cache','sliding_cached','#54816c')]:ax.plot(np.max(np.abs(a['s7_'+key]-a['s7_sliding']),axis=1),label=label,color=color)
ax.set(xlabel='Token position',ylabel='Max absolute coordinate error',title='Window 4 / relative to full sliding causal reference, seed 7');ax.legend();fig.savefig(figs/'cache-positions.png',dpi=150);plt.close(fig)
