#!/usr/bin/env python3
"""Turn app/countries.py into mobile/core/countries.go.

Generated rather than transcribed. These are three hundred lines of two-letter
keys, and a typo in one of them is a country that quietly stops matching its
own configs - the kind of mistake that looks like a missing server rather than
like a mistake. Reading the Python and printing Go means the two cannot
disagree.

    python mobile/tools/gen-countries.py

It writes mobile/core/countries.go. Run it after any change to countries.py.
"""

import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'app'))

import countries as C                                        # noqa: E402


def q(s):
    """A Go string literal. json.dumps is close enough: both languages take
    double quotes with backslash escapes, and every value here is ASCII."""
    return json.dumps(s, ensure_ascii=False)


def gomap(name, data, doc):
    out = [doc, 'var %s = map[string]string{' % name]
    for k in sorted(data):
        out.append('\t%s: %s,' % (q(k), q(data[k])))
    out.append('}\n')
    return '\n'.join(out)


def main():
    src = io.StringIO()
    src.write('''// Code generated from app/countries.py by tools/gen-countries.py.
// DO NOT EDIT - regenerate instead:
//
//\tpython mobile/tools/gen-countries.py

package core

import "strings"

''')

    src.write(gomap(
        'canonical', C.CANON,
        "// One country, one code, whatever the provider calls it. Surfshark's\n"
        "// configs say `uk` and Windscribe's say `gb`, and with both switched on\n"
        "// the United Kingdom appeared twice in the list - two rows, two flags'\n"
        "// worth of servers split between them, and no way to tell from either\n"
        "// that the other existed."))

    src.write(gomap(
        'countryNames', C.NAMES,
        "// Two-letter codes to country names. The codes come from the provider's\n"
        "// config filenames rather than ISO, which differ in one place worth\n"
        "// knowing: the United Kingdom is `uk`, not `gb`."))

    src.write(gomap(
        'cityNames', C.CITIES,
        '// Three-letter city codes, as they appear in the filenames.'))

    src.write('// Other things a person might type for the same place.\n')
    src.write('var countryAliases = map[string][]string{\n')
    for k in sorted(C.ALIASES):
        v = C.ALIASES[k]
        if isinstance(v, str):
            v = [v]
        src.write('\t%s: {%s},\n' % (q(k), ', '.join(q(x) for x in v)))
    src.write('}\n\n')

    src.write('''// Canon folds a provider's spelling into the one this app files things under.
func Canon(code string) string {
\tcode = strings.ToLower(strings.TrimSpace(code))
\tif to, ok := canonical[code]; ok {
\t\treturn to
\t}
\treturn code
}

// CountryName is the country, or the code in capitals when it is not listed.
// Wrong but never misleading, which is the right way round - inventing a
// country name for a server would put a claim on screen that nothing checked.
func CountryName(code string) string {
\tif n, ok := countryNames[Canon(code)]; ok {
\t\treturn n
\t}
\treturn strings.ToUpper(code)
}

// CityName is the city behind a three-letter code, or the code in capitals.
func CityName(code string) string {
\tif n, ok := cityNames[strings.ToLower(code)]; ok {
\t\treturn n
\t}
\treturn strings.ToUpper(code)
}

// Aliases are the other things a person might type for the same place.
func Aliases(code string) []string {
\treturn countryAliases[Canon(code)]
}
''')

    out = os.path.join(ROOT, 'mobile', 'core', 'countries.go')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(src.getvalue())

    print('%s  %d lines' % (out, len(src.getvalue().splitlines())))
    print('  countries %d   cities %d   aliases %d'
          % (len(C.NAMES), len(C.CITIES), len(C.ALIASES)))


if __name__ == '__main__':
    main()
