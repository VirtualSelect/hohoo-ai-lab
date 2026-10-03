import argparse, csv, hashlib, json, platform, subprocess
from pathlib import Path
from datetime import datetime, timezone
import numpy as np
from attention import Decoder, kv_bytes

HERE = Path(__file__).resolve().parent
P = json.loads((HERE/'protocol.json').read_text())

def run(out):
    out.mkdir(parents=True, exist_ok=False)
    sources = {p.name: hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest() for p in HERE.iterdir() if p.suffix in ('.py','.json')}
    manifest = dict(at=datetime.now(timezone.utc).isoformat(), python=platform.python_version(), numpy=np.__version__,
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=HERE,text=True).strip(),
        dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=HERE,text=True).strip()),
        sources=sources, protocol=P)
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    tokens = (np.arange(P['length']) * 7 + 3) % P['vocabulary']
    rows, arrays = [], {}
    for seed in P['seeds']:
        m = Decoder(seed, P['dimension'], P['layers'], P['vocabulary'])
        full, probs = m.full(tokens)
        changed = tokens.copy(); changed[12:] = (changed[12:] + 1) % P['vocabulary']
        masked, _ = m.full(changed)
        open_a, _ = m.full(tokens, causal=False)
        open_b, _ = m.full(changed, causal=False)
        mask_error = float(np.max(np.abs(full[:12]-masked[:12])))
        leak = float(np.max(np.abs(open_a[:12]-open_b[:12])))
        assert mask_error < P['tolerance'] and leak > .0001
        assert all(np.count_nonzero(np.triu(p,1)) == 0 for p in probs)
        m.reset(); prefix = np.array([m.full(tokens[:i+1])[0][-1] for i in range(len(tokens))])
        prefix_counts = [m.projected_rows,m.score_elements]
        m.reset(); cache=None; decoded=[]; step_rows=[]
        for i, token in enumerate(tokens):
            value, cache = m.chunk([token],cache)
            decoded.append(value[0]); step_rows.append([i+1,kv_bytes(cache)])
        decoded = np.array(decoded)
        cache_counts = [m.projected_rows,m.score_elements]
        m.reset(); chunks=[]; cc=None; start=0
        for size in [5,3,7,9]:
            value,cc=m.chunk(tokens[start:start+size],cc);chunks.extend(value);start+=size
        _,bad_cache=m.chunk(tokens[:5]); wrong,_=m.chunk(tokens[5:8],bad_cache,wrong_mask=True)
        good,_=m.chunk(tokens[5:8],bad_cache)
        # Independent reference computes all rows with a sliding causal mask.
        sliding,_ = m.full(tokens,window=P['window'])
        sc=None; actual=[]; reset=[]; bad=None; cropped=[]
        for i,token in enumerate(tokens):
            val,sc=m.chunk([token],sc,window=P['window']);actual.append(val[0])
            val,bad=m.chunk([token],bad,window=P['window'],reset_position=True);reset.append(val[0])
            begin=max(0,i-P['window']+1)
            cropped.append(m.full(tokens[begin:i+1],window=P['window'],offset=begin)[0][-1])
        actual,reset,cropped=map(np.array,(actual,reset,cropped))
        error=lambda a,b:float(np.max(np.abs(a-b)))
        result=dict(seed=seed,causal_future_error=mask_error,unmasked_future_change=leak,
            prefix_error=error(prefix,full),cache_error=error(decoded,full),chunk_error=error(chunks,full),
            wrong_chunk_mask_error=error(wrong,good),sliding_error=error(actual,sliding),
            reset_position_error=error(reset,sliding),cropped_recompute_error=error(cropped,sliding),
            prefix_projected_rows=prefix_counts[0],cached_projected_rows=cache_counts[0],
            prefix_score_elements=prefix_counts[1],cached_score_elements=cache_counts[1],
            full_kv_bytes=kv_bytes(cache),window_kv_bytes=kv_bytes(sc))
        for key in ('prefix_error','cache_error','chunk_error','sliding_error'):
            assert result[key]<P['tolerance'],result
        rows.append(result)
        arrays.update({f's{seed}_{key}': value for key,value in dict(tokens=tokens,full=full,masked=masked,unmasked=open_a,perturbed_unmasked=open_b,
            prefix=prefix,cached=decoded,chunked=np.array(chunks),wrong=wrong,good=good,sliding=sliding,sliding_cached=actual,reset=reset,cropped=cropped,
            attention=probs[0],cache_bytes=np.array(step_rows)).items()})
    np.savez_compressed(out/'arrays.npz',**arrays)
    with (out/'results.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]),lineterminator='\n');w.writeheader();w.writerows(rows)
    (out/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
    hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.name!='manifest.json'}
    (out/'sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
    print(json.dumps(rows,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);run(p.parse_args().out)
