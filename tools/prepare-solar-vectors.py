#!/usr/bin/env python3
"""Convert the bundled original Solar paths to compiled Android vector resources."""
from pathlib import Path
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr
root = Path(__file__).resolve().parents[1]
for name in ('settings', 'check', 'info', 'move', 'apps', 'wifi', 'input', 'plus'):
    svg = ET.parse(root / f'app/src/main/assets/solar/{name}.svg').getroot()
    paths = []
    for node in svg:
        tag, a = node.tag.split('}')[-1], node.attrib
        if tag == 'path':
            data = a['d']
        elif tag == 'circle':
            x, y, r = map(float, (a['cx'], a['cy'], a['r']))
            data = f'M {x-r},{y} a {r},{r} 0 1,0 {2*r},0 a {r},{r} 0 1,0 {-2*r},0'
        else:
            raise ValueError(f'Unsupported Solar element: {name}/{tag}')
        attrs = dict(pathData=data, fillColor=a.get('fill', svg.get('fill', 'none')),
                     strokeColor=a.get('stroke', 'none'), strokeWidth=a.get('stroke-width', svg.get('stroke-width', '1.5')),
                     strokeLineCap=a.get('stroke-linecap', 'butt'), strokeLineJoin=a.get('stroke-linejoin', 'miter'))
        for key in ('fillColor', 'strokeColor'):
            attrs[key] = {'none': '#00000000', 'currentColor': '#ffffff'}.get(attrs[key], attrs[key])
        paths.append('<path ' + ' '.join('android:' + k + '=' + quoteattr(v) for k, v in attrs.items()) + ' />')
    output = root / f'app/src/main/res/drawable/solar_{name}.xml'
    output.write_text('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">' + ''.join(paths) + '</vector>')
