import argparse,hashlib,json,subprocess
from pathlib import Path
H=Path(__file__).resolve().parent;R=H.parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);out=p.parse_args().out.resolve();out.mkdir(parents=True,exist_ok=False)
 subprocess.run(['E:/apache-maven-3.6.3/bin/mvn.cmd','-o','-q','package','exec:java','-Dexec.args='+str(out)],cwd=H,check=True)
 sources=list(H.rglob('src/**/*.java'))+[H/'pom.xml',H/'protocol.json',H/'run.py',H/'audit.py']
 manifest=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),sources={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in sources},files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['results.json','environment.json']})
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8')
if __name__=='__main__':main()
