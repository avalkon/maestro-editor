#!/usr/bin/env python3
"""Patch version resources of the *specific unsigned jpackage 4.7.6 launchers*.
Supports existing 2 VERSIONINFO resources + following manifest. Refuses unexpected layouts.
Not a substitute for rebuilding/signed Windows launchers using jpackage on Windows.
"""
import sys,struct,pathlib
U16=lambda b,o:struct.unpack_from('<H',b,o)[0]
U32=lambda b,o:struct.unpack_from('<I',b,o)[0]
A4=lambda n:(n+3)&~3

def node(data,start,limit=None):
    limit=len(data) if limit is None else limit
    n,vl,t=struct.unpack_from('<HHH',data,start)
    if n<10 or start+n>limit:raise ValueError(f'invalid block {start=} {n=}')
    p=start+6; key=[]
    while p+2<=start+n:
        c=U16(data,p);p+=2
        if c==0:break
        key.append(chr(c))
    key=''.join(key)
    p=A4(p)
    vb=vl*2 if t==1 else vl
    if p+vb>start+n:raise ValueError('bad value length '+key)
    val=bytes(data[p:p+vb]);p=A4(p+vb)
    ch=[]
    while p+6<=start+n:
        if U16(data,p)==0:break
        child=node(data,p,start+n)
        ch.append(child)
        p=A4(p+child['size'])
    return {'key':key,'type':t,'value':val,'children':ch,'size':n}

def build(n):
    b=bytearray(b'\0'*6)
    b+=n['key'].encode('utf-16le')+b'\0\0'
    b+=b'\0'*((-len(b))%4)
    b+=n['value']; b+=b'\0'*((-len(b))%4)
    for c in n['children']:
        b+=build(c);b+=b'\0'*((-len(b))%4)
    vlen=len(n['value'])//2 if n['type']==1 else len(n['value'])
    if len(b)>65535:raise ValueError('oversize')
    struct.pack_into('<HHH',b,0,len(b),vlen,n['type'])
    return bytes(b)

def find_children(n,k):
    if n['key']==k:return [n]
    return [child for c in n['children'] for child in find_children(c,k)]

def patch(data, version='4.7.7-B1'):
    b=bytearray(data)
    nt=U32(b,0x3c)
    if b[nt:nt+4]!=b'PE\0\0':raise ValueError('not PE')
    opt=nt+24; sizeopt=U16(b,nt+20)
    if U16(b,opt)!=0x20b:raise ValueError('expected PE32+')
    rva,rsz=struct.unpack_from('<II',b,opt+112+2*8)
    sectiontable=opt+sizeopt; rsec=None
    for i in range(U16(b,nt+6)):
        s=sectiontable+40*i
        if b[s:s+8].rstrip(b'\0')==b'.rsrc':rsec=s;break
    if rsec is None:raise ValueError('missing rsrc')
    vsize,svaddr,rawsize,rawptr=struct.unpack_from('<IIII',b,rsec+8)
    if svaddr!=rva:raise ValueError('unexpected rsrc RVA')
    def entry(typeid):
        base=rawptr
        nname,nid=struct.unpack_from('<HH',b,base+12)
        p=base+16;found=None
        for _ in range(nname+nid):
            k,x=struct.unpack_from('<II',b,p);p+=8
            if k==typeid:found=x;break
        if found is None:raise ValueError('resource missing '+str(typeid))
        def descend(rel):
            dirpos=base+(rel&0x7fffffff)
            nn,ni=struct.unpack_from('<HH',b,dirpos+12)
            leaves=[]
            for i in range(nn+ni):
                k,x=struct.unpack_from('<II',b,dirpos+16+i*8)
                if x&0x80000000:leaves+=descend(x)
                else:leaves.append((base+x, k))
            return leaves
        return descend(found)
    vers=[]
    for pos,lang in entry(16):
        a,sz=struct.unpack_from('<II',b,pos)
        vers.append((pos,rawptr+a-rva,sz,lang))
    man=[]
    for pos,lang in entry(24):
        a,sz=struct.unpack_from('<II',b,pos)
        man.append((pos,rawptr+a-rva,sz,lang))
    vers.sort(key=lambda t:t[1]);man.sort(key=lambda t:t[1])
    if len(vers)!=2 or len(man)!=1:raise ValueError('unexpected VERSIONINFO/manifest count')
    sections=vers+man
    if any(sections[i][1]+sections[i][2]!=sections[i+1][1] for i in range(2)):
        raise ValueError('resource values not contiguous; refusing to rewrite')
    if not all(b[s:s+2]!=b'\0\0' for _,s,sz,lang in vers):raise ValueError('invalid version data')
    news=[]
    for idx,(pos,off,sz,lang) in enumerate(vers):
        if idx==1:  # Second VERSIONINFO belongs to the bundled jpackage runtime; leave it intact.
            news.append(bytes(b[off:off+sz])); continue
        top=node(b[off:off+sz],0)
        if top['key']!='VS_VERSION_INFO':raise ValueError('wrong version root')
        for key in ('FileVersion','ProductVersion'):
            found=find_children(top,key)
            if len(found)!=1 or '4.7.6' not in found[0]['value'].decode('utf-16le'):
                raise ValueError('unexpected '+key+' string')
            found[0]['value']=(version+'\0').encode('utf-16le')
        fixed=bytearray(top['value'])
        if U32(fixed,0)!=0xFEEF04BD:raise ValueError('wrong fixed version signature')
        # FileVersionMS/LS and ProductVersionMS/LS in VS_FIXEDFILEINFO.
        struct.pack_into('<IIII',fixed,8,(4<<16)|7,(7<<16)|1,(4<<16)|7,(7<<16)|1)
        top['value']=bytes(fixed)
        new=build(top)
        # roundtrip check
        check=node(new,0)
        assert all(find_children(check,k)[0]['value'].decode('utf-16le').rstrip('\0')==version for k in ('FileVersion','ProductVersion'))
        news.append(new)
    end=man[0][1]+man[0][2]
    payload=news[0]+news[1]+bytes(b[man[0][1]:end])
    growth=len(payload)-(end-vers[0][1])
    # 0 padding must remain until next raw section
    next_raw=min([U32(b,sectiontable+40*i+20) for i in range(U16(b,nt+6)) if U32(b,sectiontable+40*i+20)>rawptr],default=len(b))
    if end+growth>next_raw or any(c not in b'PADDINGX\x00' for c in b[end:next_raw]):
        raise ValueError('insufficient clean rsrc padding')
    b[vers[0][1]:vers[0][1]+len(payload)]=payload
    b[vers[0][1]+len(payload):end+growth]=b'\0'*(end+growth-(vers[0][1]+len(payload)))
    offs=[vers[0][1],vers[0][1]+len(news[0]),vers[0][1]+len(news[0])+len(news[1])]
    for (pos,_,sz,lang),off,newsz in zip(sections,offs,[len(news[0]),len(news[1]),man[0][2]]):
        struct.pack_into('<II',b,pos,rva+(off-rawptr),newsz)
    struct.pack_into('<I',b,rsec+8,vsize+growth)
    struct.pack_into('<I',b,opt+112+2*8,rsz+growth)
    return bytes(b),growth

if __name__=='__main__':
    if len(sys.argv)!=3:sys.exit('usage: patch_pe_version.py ORIGINAL.exe UPDATED.exe')
    out,g=patch(pathlib.Path(sys.argv[1]).read_bytes())
    pathlib.Path(sys.argv[2]).write_bytes(out)
    print(f'Updated PE resources (resource growth {g} bytes)')
