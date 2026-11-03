#!/usr/bin/env python3
"""Offline static input preflight. Reads explicit local paths only.
Never downloads, extracts, runs build tools, writes resources or accepts a license.
Exit0: listed static checks passed, not a build/reproducibility certificate. Exit2: missing/incorrect input.
"""
import argparse,hashlib,json,ntpath,os,re,stat,sys
from pathlib import Path,PurePosixPath
def _query_dos_device(drive):
 # Local DOS-device namespace metadata, never a caller-supplied UNC root.
 import ctypes
 kernel=ctypes.WinDLL('kernel32.dll',use_last_error=True)
 query=kernel.QueryDosDeviceW
 query.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p,ctypes.c_ulong];query.restype=ctypes.c_ulong
 buffer=ctypes.create_unicode_buffer(32768)
 n=query(drive,buffer,len(buffer))
 if not n:raise OSError(ctypes.get_last_error(),'QueryDosDeviceW cannot confirm local drive')
 return tuple(x for x in buffer[:n].split('\0') if x)
def _drive_type(drive):
 import ctypes
 kernel=ctypes.WinDLL('kernel32.dll',use_last_error=True)
 kind=kernel.GetDriveTypeW;kind.argtypes=[ctypes.c_wchar_p];kind.restype=ctypes.c_uint
 return kind(drive+'\\')
def local_absolute(path,base=None,layout_parent=False):
 # No filesystem calls, CWD lookup, environment expansion, or path resolution here.
 # Fail closed outside the controlled Windows fixed-volume profile.
 if sys.platform!='win32':raise ValueError('unsupported platform: controlled local Windows fixed volumes only')
 raw=os.fspath(path)
 if not isinstance(raw,str) or not raw or any(ord(c)<32 for c in raw):
  raise ValueError('empty/control-character path refused')
 value=raw.replace('/','\\')
 if value.startswith('\\'):raise ValueError('UNC, rooted, NT/device and device-UNC paths refused before filesystem access')
 absolute=re.match(r'^[A-Za-z]:\\',value) is not None
 if not absolute:
  if ':' in value or base is None:raise ValueError('explicit absolute DOS-drive path required; no CWD fallback')
  anchor=local_absolute(base)
  value=ntpath.join(str(anchor),value)
 # Reject ambiguous Win32 devices/ADS, trailing-dot aliases, and unknown parent traversal.
 tail=value[3:]
 for component in tail.split('\\'):
  if not component or component=='.':continue
  if component=='..':
   if layout_parent:continue
   raise ValueError('parent traversal refused in input path')
  if ':' in component or '*' in component or '?' in component or component.endswith((' ','.')):
   raise ValueError('ambiguous Win32 path component refused')
  name=component.split('.',1)[0].upper()
  if re.fullmatch(r'(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\$|CONOUT\$)',name):
   raise ValueError('DOS device name refused')
 value=ntpath.normpath(value);drive=value[:2].upper()
 try:
  targets=_query_dos_device(drive)
  # Reject redirects, SUBST/aliases, multiple/unknown targets and all non-fixed types.
  if len(targets)!=1 or not re.fullmatch(r'\\Device\\HarddiskVolume[0-9]+',targets[0]):
   raise ValueError('drive is redirected, aliased, network, or cannot be confirmed local: '+drive)
  if _drive_type(drive)!=3:raise ValueError('drive is not a confirmed local fixed volume: '+drive)
 except (OSError,AttributeError) as error:raise ValueError('local drive classification failed closed: '+drive+': '+str(error)) from error
 return Path(value)
def plain_path(path):
 path=local_absolute(path)
 for p in reversed((path,*path.parents)):
  try:s=p.lstat()
  except FileNotFoundError:continue
  # Offline/recall/unpinned placeholders can hydrate through network-backed filters.
  if stat.S_ISLNK(s.st_mode) or getattr(s,'st_file_attributes',0)&(0x400|0x1000|0x40000|0x400000|0x100000):
   raise ValueError('link/reparse/offline/recall/unpinned path refused: '+str(p))
 return path
def relative_absolute(root,value):
 if not isinstance(value,str) or not value or '\\' in value:raise ValueError('invalid relative path')
 p=PurePosixPath(value)
 if p.is_absolute() or any(x in ('..','') for x in p.parts) or ':' in value:raise ValueError('escaping/absolute manifest path refused: '+value)
 return local_absolute(Path(root).joinpath(*p.parts))
def relative(root,value):
 return plain_path(relative_absolute(root,value))
def read_json(path):
 p=plain_path(path)
 if not p.is_file():raise ValueError('required JSON file missing: '+str(p))
 return json.loads(p.read_text(encoding='utf-8-sig'))
def file_check(path,entry):
 result={'id':entry['id'],'path':str(path),'required':entry.get('required',True)}
 try:
  p=plain_path(path)
  if not p.is_file():raise ValueError('required regular file missing')
  s=p.stat()
  if 'bytes' in entry and s.st_size!=entry['bytes']:raise ValueError('byte length mismatch; expected '+str(entry['bytes'])+', actual '+str(s.st_size))
  algorithms=[name for name in ('sha256','sha1') if entry.get(name)]
  if not algorithms:raise ValueError('no pinned content hash supplied')
  digests={}
  for name in algorithms:
   if not re.fullmatch('[0-9a-f]{'+str(64 if name=='sha256' else 40)+'}',entry[name]):raise ValueError('invalid expected '+name)
   digests[name]=hashlib.new(name)
  with p.open('rb') as stream:
   for data in iter(lambda:stream.read(1024*1024),b''):
    for d in digests.values():d.update(data)
  actual={name:d.hexdigest() for name,d in digests.items()}
  if any(actual[name]!=entry[name] for name in algorithms):raise ValueError('content hash mismatch')
  result.update(ok=True,bytes=s.st_size,hashes=actual)
 except (OSError,ValueError) as error:result.update(ok=False,error=str(error))
 return result
def inspect(project,resources,manifest_path,all_maven=False):
 # Validate every top-level parameter plus this script before any lstat/open/stat.
 script=local_absolute(__file__)
 project=local_absolute(project);resources=local_absolute(resources);manifest_path=local_absolute(manifest_path)
 plain_path(script);manifest_path=plain_path(manifest_path);bundle=manifest_path.parent
 m=read_json(manifest_path);cfg=read_json(resources)
 if not isinstance(m,dict) or not isinstance(cfg,dict):raise ValueError('manifest/resources must be JSON objects')
 if not isinstance(cfg.get('roots',{}),dict):raise ValueError('roots must be a JSON object')
 if m.get('schema')!=1:raise ValueError('unsupported manifest schema')
 project=plain_path(project);checks=[];issues=[]
 if not project.is_dir():issues.append({'id':'project','path':str(project),'error':'target project directory missing; nothing created'})
 index=read_json(relative(bundle,m['source_files']))
 roots={'project':project}
 for key,value in cfg.get('roots',{}).items():
  if key not in ('downloads','sdk','gradle','jdk','vendor','maven'):raise ValueError('unknown resource root: '+key)
  if not isinstance(value,str) or not value.strip():raise ValueError('empty resource root: '+key)
  roots[key]=local_absolute(value,base=resources.parent)
 # Screen nested paths before opening any source/resource file.
 for item in index:relative_absolute(project,item['path'])
 for entry in m['files']:
  if entry['root'] in roots:relative_absolute(roots[entry['root']],entry['relative'])
 for root in roots.values():plain_path(root)
 for item in index:
  checks.append(file_check(relative(project,item['path']),{'id':'source:'+item['path'],'bytes':item['bytes'],'sha256':item['sha256']}))
 for entry in m['files']:
  key=entry['root']
  if key not in roots:
   issues.append({'id':entry['id'],'error':'explicit resource root not configured: '+key});continue
  checks.append(file_check(relative(roots[key],entry['relative']),entry))
 if 'vendor' in roots:
  expected=local_absolute(m['vendor_from_project'],base=project,layout_parent=True)
  if roots['vendor']!=expected:issues.append({'id':'vendor-layout','expected_path':str(expected),'configured_path':str(roots['vendor']),'error':'build.gradle/prepare script relative layout mismatch; no copying or path modification performed'})
 reviews=cfg.get('license_reviews',[])
 if not isinstance(reviews,list) or any(not isinstance(x,str) for x in reviews):raise ValueError('license_reviews must be an explicit list of reviewed license IDs')
 for name in m.get('required_license_reviews',[]):
  if name not in reviews:issues.append({'id':'license-review:'+name,'error':'no user-supplied review attestation; SDK acceptance files are neither read nor written'})
 if all_maven:
  inv=read_json(relative(bundle,m['maven_inventory']))
  for row in inv['artifacts']:relative_absolute(roots.get('maven',project),row['cache_relative'])
  if 'maven' not in roots:issues.append({'id':'maven-inventory','error':'explicit maven root missing'})
  else:
   for row in inv['artifacts']:
    checks.append(file_check(relative(roots['maven'],row['cache_relative']),{'id':'observed-maven:'+row['repository_relative'],'bytes':row['bytes'],'sha256':row['sha256']}))
 failed=[c for c in checks if not c['ok'] and c['required']];ok=not failed and not issues
 return {'status':'PASS_STATIC_INPUTS_ONLY' if ok else 'FAIL_INCOMPLETE_INPUTS','source_commit':m['source_commit'],'source_tree':m['source_tree'],'project':str(project),'checks':checks,'issues':issues,'failed_required_files':len(failed),'issue_count':len(issues),'source_files_listed':len(index),'all_historical_maven_checked':all_maven,'license_review_kind':'configuration attestation only; no acceptance or agreement action','limitations':m.get('limitations',[]),'path_policy':'controlled Windows DOS paths on verified HarddiskVolume + DRIVE_FIXED only; UNC/device/redirected/unknown paths refused before target filesystem calls; no concurrent remapping assumed','patch_tool_check':'NOT_PERFORMED: documentation gap only','preparation_chain_validated':False,'path_race_boundary':'reparse checks and open are not atomic; trusted local runtime and stable mounts/directories required','build_executed':False,'fresh_environment_validated':False,'bitwise_reproducibility_established':False},0 if ok else 2
def main(argv=None):
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--project',required=True,help='explicit absolute existing checkout on a local Windows fixed volume')
 parser.add_argument('--resources',default=str(Path(__file__).with_name('resources.example.json')))
 parser.add_argument('--manifest',default=str(Path(__file__).with_name('manifest.json')))
 parser.add_argument('--all-observed-maven',action='store_true',help='also hash the historical375-file cache set; not a dependency-closure proof')
 args=parser.parse_args(argv)
 try:report,code=inspect(args.project,args.resources,args.manifest,args.all_observed_maven)
 except (OSError,ValueError,KeyError,TypeError) as error:
  report={'status':'FAIL_CONFIGURATION','error':str(error),'build_executed':False,'network_or_install_performed':False,'path_policy':'Windows local fixed volumes only; invalid inputs refused before target filesystem queries'};code=2
 print(json.dumps(report,ensure_ascii=False,indent=2));return code
if __name__=='__main__':raise SystemExit(main())

