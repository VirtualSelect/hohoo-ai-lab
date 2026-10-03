"""One-command offline reproduction, including source provenance and audit."""
import argparse,hashlib,json,os,shutil,subprocess,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--maven',default='mvn');a=p.parse_args()
out=a.out.resolve()
if out.exists():raise SystemExit('Use a new output directory; existing evidence is never overwritten.')
if '"' in str(out):raise SystemExit('Unsupported quote in output path.')
sha=lambda path:hashlib.sha256(path.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
sources=list((HERE/'src').rglob('*.java'))+[HERE/n for n in ('corpus.json','protocol.json','pom.xml','audit.py','run.py')]
manifest=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=HERE,text=True).strip(),
    dirtyBeforeRun=bool(subprocess.check_output(['git','status','--porcelain'],cwd=HERE,text=True).strip()),
    sources={x.relative_to(HERE).as_posix():sha(x) for x in sources},scope='Local Java8 fixtures; zero provider requests')
out.mkdir(parents=True)
mvn=shutil.which(a.maven)
if not mvn:raise SystemExit('Maven not found; pass --maven /path/to/mvn and set JAVA_HOME to Java8.')
subprocess.run([mvn,'-q','compile','exec:java','-Dexec.args="'+str(out/'results.json')+'"'],cwd=HERE,check=True)
manifest['resultsSha256']=sha(out/'results.json')
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf8',newline='\n')
subprocess.run([sys.executable,str(HERE/'audit.py'),str(out)],check=True)
