import argparse,hashlib,json,os,subprocess
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--maven',default='mvn');a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
 subprocess.run([a.maven,'-q','package','dependency:copy-dependencies'],cwd=HERE,check=True)
 java=Path(os.environ['JAVA_HOME'])/'bin/java.exe' if os.name=='nt' else 'java'
 cp=os.pathsep.join([str(HERE/'target/classes'),str(HERE/'target/dependency/*')])
 subprocess.run([str(java),'-cp',cp,'com.hohoo.ailab.stream.Suite',str(out)],cwd=HERE,check=True)
 sources=list(HERE.rglob('*.java'))+list(HERE.glob('*.py'))+[HERE/'pom.xml',HERE/'protocol.json']
 m=dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),sources={s.relative_to(ROOT).as_posix():hashlib.sha256(s.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for s in sources},files={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in out.iterdir()})
 (out/'manifest.json').write_text(json.dumps(m,indent=2)+'\n',encoding='utf8')
if __name__=='__main__':main()
