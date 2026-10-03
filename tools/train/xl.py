import zipfile,re,sys
import xml.etree.ElementTree as ET
def read_xlsx(path):
    z=zipfile.ZipFile(path); ns={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
    ss=[]
    if 'xl/sharedStrings.xml' in z.namelist():
        for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si',ns):
            ss.append(''.join(t.text or '' for t in si.iter('{%s}t'%ns['m'])))
    sh=ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
    rows=[]
    for r in sh.iter('{%s}row'%ns['m']):
        d={}
        for c in r.findall('m:c',ns):
            col=re.match(r'[A-Z]+',c.get('r')).group()
            v=c.find('m:v',ns); t=c.get('t')
            if t=='inlineStr': val=''.join(x.text or '' for x in c.iter('{%s}t'%ns['m']))
            elif v is None: continue
            elif t=='s': val=ss[int(v.text)]
            else: val=v.text
            d[col]=val
        rows.append(d)
    return rows
if __name__=='__main__':
    for r in read_xlsx(sys.argv[1])[:6]: print(r)
