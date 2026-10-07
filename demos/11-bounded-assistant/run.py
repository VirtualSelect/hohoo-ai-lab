import argparse,hashlib,json,os,platform,subprocess
from pathlib import Path
H=Path(__file__).resolve().parent;R=H.parents[1]
p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--gson',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();out=a.out.resolve();out.parent.mkdir(parents=True,exist_ok=True);classes=H/'target/classes';classes.mkdir(parents=True,exist_ok=True)
suffix='.exe' if os.name=='nt' else '';sources=list((H/'src').rglob('*.java'));java=str(a.java_home/('bin/java'+suffix));javac=str(a.java_home/('bin/javac'+suffix));cp=str(classes)+os.pathsep+str(a.gson)
subprocess.run([javac,'-encoding','UTF-8','-cp',str(a.gson),'-d',str(classes)]+list(map(str,sources)),check=True)
subprocess.run([java,'-cp',cp,'com.hohoo.ailab.bounded.Suite',str(out)],check=True)
crashes=[]
for mode,expected in [('before',1),('partial',1),('committed',2)]:
 path=out/(mode+'.log');r=subprocess.run([java,'-cp',cp,'com.hohoo.ailab.bounded.CrashWriter',str(path),mode]);assert r.returncode==23
 count=int(subprocess.check_output([java,'-cp',cp,'com.hohoo.ailab.bounded.CrashWriter',str(path),'verify'],text=True).strip());assert count==expected
 crashes.append(dict(mode=mode,exit_code=r.returncode,recovered_turns=count))
(out/'crashes.json').write_text(json.dumps(crashes,indent=2)+'\n')
sha=lambda p:hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
manifest=dict(java=subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT,text=True).strip(),platform=platform.platform(),commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),sources={p.relative_to(R).as_posix():sha(p) for p in sources+[H/'run.py',H/'audit.py',H/'protocol.json']},files={p.name:sha(p) for p in out.iterdir() if p.is_file()})
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(crashes)
