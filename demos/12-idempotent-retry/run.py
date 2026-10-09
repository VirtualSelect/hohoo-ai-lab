"""Real loopback HTTP, concurrent requests and process crashes; no external calls."""
import argparse, concurrent.futures, hashlib, http.client, json, pathlib, platform, subprocess, sys, tempfile, threading
H = pathlib.Path(__file__).resolve().parent
ROOT = H.parents[1]
P = json.loads((H / 'protocol.json').read_text())

def request(port, key=None, body=b'add-one', **headers):
    h = dict(headers)
    if key is not None: h['Idempotency-Key'] = key
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=8)
    try:
        connection.request('POST', '/effect', body=body, headers=h)
        response = connection.getresponse()
        return dict(status=response.status, body=response.read().decode(), replay=response.getheader('Idempotency-Replayed'))
    except (OSError, http.client.HTTPException) as error:
        return dict(error=type(error).__name__)
    finally: connection.close()

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--java-home', required=True); parser.add_argument('--out', type=pathlib.Path, default=H/'evidence'); args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    exe = '.exe' if sys.platform == 'win32' else ''
    java = pathlib.Path(args.java_home)/'bin'/('java'+exe)
    classes = H/'target'/'classes'; classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(java.parent/('javac'+exe)), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(classes), str(H/'IdempotencyServer.java')], check=True)
    processes=[]; results=[]
    def start(file):
        process = subprocess.Popen([str(java), '-cp', str(classes), 'IdempotencyServer', str(file)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        processes.append(process); ready=process.stdout.readline().strip()
        if not ready.startswith('READY '): raise RuntimeError(ready+process.stderr.read())
        return process, int(ready.split()[1])
    def stop(process):
        if process.poll() is None: process.terminate()
        process.wait(timeout=10)
    with tempfile.TemporaryDirectory() as temp:
        try:
            for name in P['cases']:
                file=pathlib.Path(temp)/(name+'.counter'); process,port=start(file); calls=[]; exit_code=None
                if name.endswith('lost_reply'):
                    key=None if name.startswith('unkeyed') else 'same'
                    calls=[request(port,key,**{'X-Test-Drop':'yes'}),request(port,key)]
                elif name=='concurrent_duplicate':
                    barrier=threading.Barrier(P['concurrent_clients'])
                    def send(_):
                        barrier.wait(); return request(port,'parallel',**{'X-Test-Slow':'yes'})
                    with concurrent.futures.ThreadPoolExecutor(P['concurrent_clients']) as pool: calls=list(pool.map(send,range(P['concurrent_clients'])))
                elif name=='body_conflict':
                    calls=[request(port,'same'),request(port,'same',b'different')]
                elif name in ('restart_after_reply','crash_after_effect'):
                    calls.append(request(port,'same',**({'X-Test-Crash':'yes'} if name.startswith('crash') else {})))
                    if name.startswith('crash'): exit_code=process.wait(timeout=10)
                    else: stop(process)
                    process,port=start(file); calls.append(request(port,'same'))
                else:
                    calls=[request(port,'bad key'),request(port,'large',b'x'*(P['max_body_bytes']+1))]
                    calls += [request(port,'key-'+str(i)) for i in range(P['registry_capacity'])]
                    calls += [request(port,'overflow'), request(port,'key-0')]
                stop(process)
                results.append(dict(case=name,calls=calls,counter=int(file.read_text()) if file.exists() else 0,crash_exit_code=exit_code))
        finally:
            for process in processes: stop(process)
    (args.out/'results.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    norm=lambda p:p.read_bytes().replace(b'\r\n',b'\n')
    manifest=dict(protocol=P,source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),python=sys.version,platform=platform.platform(),java=subprocess.check_output([str(java),'-version'],stderr=subprocess.STDOUT,text=True).strip(),sources={p.name:hashlib.sha256(norm(p)).hexdigest() for p in H.iterdir() if p.suffix in ('.java','.py','.json')},files={'results.json':hashlib.sha256((args.out/'results.json').read_bytes()).hexdigest()})
    (args.out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(json.dumps([{'case':r['case'],'counter':r['counter']} for r in results],indent=2))
if __name__=='__main__': main()
