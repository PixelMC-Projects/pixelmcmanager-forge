"""Make an immutable web-only service patch without recompiling any Java class."""
from pathlib import Path
import argparse,hashlib,io,json,zipfile

ROOT=Path(__file__).resolve().parents[1]
BASE_JAR_SHA256='4ce5d55f90a0bb9c079e996acce259175ca1bacc6e68f7fa277a74e3ffeddf82'
def sha(data):return hashlib.sha256(data).hexdigest()
def put(z,name,data):
    info=zipfile.ZipInfo(name,(2026,10,26,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;z.writestr(info,data)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('base_zip',type=Path);parser.add_argument('--version',default='0.5.2-web.1');args=parser.parse_args()
    output=ROOT/'build/distributions';output.mkdir(exist_ok=True,parents=True)
    with zipfile.ZipFile(args.base_zip) as base:
        original=base.read('service.jar');assert sha(original)==BASE_JAR_SHA256,'Use the verified original v0.5.2 service ZIP'
        data=io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(data,'w',zipfile.ZIP_DEFLATED) as new:
            for name in old.namelist():
                if name.startswith('web/'):continue
                value=old.read(name)
                if name=='META-INF/MANIFEST.MF':value=value.replace(b'Implementation-Version: 0.5.2',('Implementation-Version: '+args.version).encode())
                put(new,name,value)
            for p in sorted((ROOT/'web-console/dist/client').rglob('*')):
                if p.is_file():
                    name=p.relative_to(ROOT/'web-console/dist/client').as_posix();assert 'fortune-preview' not in name
                    put(new,'web/'+name,p.read_bytes())
        patched=data.getvalue()
        with zipfile.ZipFile(io.BytesIO(original)) as old,zipfile.ZipFile(io.BytesIO(patched)) as new:
            original_names={n for n in old.namelist() if not n.startswith('web/') and n!='META-INF/MANIFEST.MF'}
            assert original_names=={n for n in new.namelist() if not n.startswith('web/') and n!='META-INF/MANIFEST.MF'}
            assert all(old.read(n)==new.read(n) for n in original_names)
            classes=sum(n.endswith('.class') for n in original_names)
        jar=output/f'pixelmc-manager-service-{args.version}.jar';jar.write_bytes(patched)
        package=output/f'pixelmc-manager-service-{args.version}.zip'
        with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:
            for name in base.namelist():
                value=patched if name=='service.jar' else base.read(name)
                if name=='README.md':value+=('\n\nWeb-only patch '+args.version+': DailyFortunes avatars use the approved 32px list / 40px detail sizes. Java classes and storage/bridge behavior are byte-identical to 0.5.2. Compatible with existing 0.5.2 Forge/Fabric game mods; no game JAR update is needed. The original v0.5.2 release remains unchanged. Patch source: https://github.com/PixelMC-Projects/pixelmcmanager-forge/tree/v'+args.version+'\n').encode()
                put(z,name,value)
        report={'version':args.version,'baseJarSha256':BASE_JAR_SHA256,'patchedJarSha256':sha(patched),'zipSha256':sha(package.read_bytes()),'unchangedJavaClasses':classes,'allNonWebResourcesUnchanged':True,'jar':str(jar),'zip':str(package)}
        (output/f'service-{args.version}-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
