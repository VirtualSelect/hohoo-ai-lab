import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path
H=Path(__file__).resolve().parent;R=H.parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True)
 p.add_argument('--maven',default=os.environ.get('MAVEN_CMD','mvn'));p.add_argument('--offline',action='store_true');a=p.parse_args()
 maven=shutil.which(a.maven)
 if not maven:p.error('Maven not found: add it to PATH or pass --maven /path/to/mvn')
 out=a.out.resolve()
 if '"' in str(out):p.error('Output path must not contain a double quote')
 out.mkdir(parents=True,exist_ok=False)
 subprocess.run([maven]+(['-o'] if a.offline else [])+['-q','package','exec:java','-Devidence.out='+out.as_posix()],cwd=H,check=True)
 sources=list(H.rglob('src/**/*.java'))+[H/'pom.xml',H/'protocol.json',H/'run.py',H/'audit.py']
 manifest=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),sources={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in sources},files={n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['results.json','environment.json']})
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8')
if __name__=='__main__':main()
