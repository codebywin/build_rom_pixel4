import xml.etree.ElementTree as ET

tree = ET.parse('window_dump.xml')
root = tree.getroot()

import sys
for elem in root.iter('node'):
    text = elem.attrib.get('text', '')
    bounds = elem.attrib.get('bounds', '')
    res_id = elem.attrib.get('resource-id', '')
    clickable = elem.attrib.get('clickable', '')
    if text or clickable == 'true':
        line = f"text='{text}', id='{res_id}', bounds={bounds}, clickable={clickable}\n"
        sys.stdout.buffer.write(line.encode('utf-8'))
