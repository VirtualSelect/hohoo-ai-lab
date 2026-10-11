"""Execute real Java child processes and loopback HTTP. No third-party services."""
import argparse, concurrent.futures, hashlib, json, pathlib, platform, socket, subprocess, sys
H = pathlib.Path(__file__).resolve().parent
ROOT = H.parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    classes = H/'target'/'classes'; classes.mkdir(parents=True, exist_ok=True)
    suffix = '.exe' if sys.platform == 'win32' else ''
    java = pathlib.Path(args.java_home)/'bin'/('java'+suffix)
    subprocess.run([str(java.parent/('javac'+suffix)), '-encoding','UTF-8','-source','8','-target','8','-d',str(classes),str(H/'Ledger.java'),str(H/'Workflow.java')], check=True)
    command = [str(java), '-cp', str(classes), 'Workflow']
    calls = []; rows = []; servers = []
    def call(*parts, expected=0):
        r = subprocess.run(command+list(map(str,parts)), capture_output=True, text=True, timeout=20)
        # Store relative paths only. No environment or credentials are collected.
        calls.append(dict(args=[str(p).replace(str(args.out), '<OUT>') for p in parts],exit=r.returncode,stdout=r.stdout.strip(),stderr=r.stderr.strip()))
        if expected is not None: assert r.returncode == expected, calls[-1]
        return r
    def apply(file, key='turn-1', body='add-one', fault='none', expected=0):
        return call('apply',file,key,body,fault,expected=expected)
    def count(file): return int(call('count',file).stdout)
    def start(file, keyed=True):
        p = subprocess.Popen(command+['serve',str(file),str(keyed).lower()],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        servers.append(p); line=p.stdout.readline().strip()
        assert line.startswith('READY '), line
        return p,int(line.split()[1])
    def stop(p):
        p.terminate();p.wait(timeout=10)
    try:
        for fault in ['before','partial','after']:
            f=args.out/f'ledger-{fault}.wal'
            apply(f,fault=fault,expected=23); apply(f)
            rows.append(dict(section='ledger',case=fault,count=count(f)))
        f=args.out/'concurrent.wal'
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda _:apply(f), range(8)))
        rows.append(dict(section='ledger',case='concurrent',count=count(f)))
        rejected=apply(f,body='different',expected=None)
        rows.append(dict(section='ledger',case='conflict',rejected=rejected.returncode!=0,count=count(f)))
        corrupt=args.out/'corrupt.wal'; data=bytearray(f.read_bytes());data[-1]^=1;corrupt.write_bytes(data)
        rejected=call('count',corrupt,expected=None)
        rows.append(dict(section='ledger',case='corrupt',rejected=rejected.returncode!=0,unchanged=corrupt.read_bytes()==data))
        frames=f.read_bytes(); prefixes=args.out/'prefixes';prefixes.mkdir()
        for length in range(len(frames)):
            prefix=prefixes/f'{length}.wal';prefix.write_bytes(frames[:length]);apply(prefix)
            assert prefix.read_bytes()==frames
        rows.append(dict(section='ledger',case='truncated_prefixes',prefixes=len(frames),exact_recovery=True))
        for case in ['keyed_delivery','unkeyed_delivery','receiver_restart','unavailable','receiver_conflict']:
            d=args.out/case;d.mkdir();receiver=d/'receiver.wal'
            apply(d/'intents.wal'); p,port=start(receiver,case!='unkeyed_delivery')
            if case=='unavailable':
                stop(p)
                failure=call('resume',d,port,'none',expected=None);assert failure.returncode!=0
                assert count(d/'history.wal')==0 and count(d/'receipts.wal')==0
                p,port=start(receiver)
                call('resume',d,port,'none')
            elif case=='receiver_conflict':
                apply(receiver,body='incompatible')
                failure=call('resume',d,port,'none',expected=None);assert failure.returncode!=0
            else:
                call('resume',d,port,'delivery',expected=23)
                if case=='receiver_restart':stop(p);p,port=start(receiver)
                call('resume',d,port,'none')
            stop(p)
            rows.append(dict(section='outbox',case=case,effects=count(receiver),receipts=count(d/'receipts.wal'),history=count(d/'history.wal')))
        for fault in ['delivery','receipt','history','two_intents','parallel_resume']:
            d=args.out/('recover-'+fault);d.mkdir();apply(d/'intents.wal');p,port=start(d/'receiver.wal')
            if fault=='two_intents':apply(d/'intents.wal','turn-2','second-question')
            if fault in ['delivery','receipt','history']:
                call('resume',d,port,fault,expected=23)
            if fault=='parallel_resume':
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                    list(pool.map(lambda _:call('resume',d,port,'none'),range(8)))
            else:call('resume',d,port,'none')
            # Replay is idempotent for all three local projections.
            call('resume',d,port,'none');stop(p)
            rows.append(dict(section='history',case=fault,effects=count(d/'receiver.wal'),receipts=count(d/'receipts.wal'),history=count(d/'history.wal')))
    finally:
        for p in servers:
            if p.poll() is None:stop(p)
    (args.out/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
    (args.out/'processes.json').write_text(json.dumps(calls,indent=2)+'\n')
    sources={p.name:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in H.iterdir() if p.suffix in ('.java','.py','.json')}
    files={p.relative_to(args.out).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in args.out.rglob('*') if p.is_file() and p.name!='manifest.json'}
    manifest=dict(protocol=json.loads((H/'protocol.json').read_text()),source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),platform=platform.platform(),java=subprocess.run([str(java),'-version'],capture_output=True,text=True).stderr,python=sys.version,sources=sources,files=files)
    (args.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(rows,indent=2))
if __name__=='__main__':main()
