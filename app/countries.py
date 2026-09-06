"""Two-letter codes to country names, and to the cities behind them.

The codes come from the provider's config filenames rather than ISO, which
differ in one place worth knowing: the United Kingdom is `uk`, not `gb`.

Anything unlisted falls back to the code in capitals. That is wrong but never
misleading, which is the right way round - inventing a country name for a
server would put a claim on screen that nothing checked.
"""

# One country, one code, whatever the provider calls it.
#
# Surfshark's configs say `uk` and Windscribe's say `gb`, and with both
# switched on the United Kingdom appeared twice in the list - two rows, two
# flags' worth of servers split between them, and no way to tell from either
# that the other existed. The docstring above already settles which one wins:
# the codes here come from the config filenames, and in those it is `uk`.
#
# Applied where a code is read rather than where it is written, so that
# configs pinned before this existed are filed with the ones pinned after.
CANON = {'gb': 'uk'}


def canon(code):
    code = (code or '').lower()
    return CANON.get(code, code)


NAMES = {
    'ad': 'Andorra',        'ae': 'United Arab Emirates',
    'af': 'Afghanistan',    'al': 'Albania',
    # Antarctica is not a joke entry: Windscribe lists one, and without a
    # name here the country picker offers a row reading "AQ".
    'am': 'Armenia',        'aq': 'Antarctica',
    'ar': 'Argentina',      'at': 'Austria',
    'au': 'Australia',      'az': 'Azerbaijan',
    'ba': 'Bosnia and Herzegovina',
    'bd': 'Bangladesh',     'be': 'Belgium',
    'bg': 'Bulgaria',       'bh': 'Bahrain',
    'bn': 'Brunei',         'bo': 'Bolivia',
    'br': 'Brazil',         'bs': 'Bahamas',
    'bt': 'Bhutan',         'by': 'Belarus',
    'bz': 'Belize',         'ca': 'Canada',
    'ch': 'Switzerland',    'cl': 'Chile',
    'cn': 'China',          'co': 'Colombia',
    'cr': 'Costa Rica',     'cy': 'Cyprus',
    'cz': 'Czechia',        'de': 'Germany',
    'dk': 'Denmark',        'do': 'Dominican Republic',
    'dz': 'Algeria',        'ec': 'Ecuador',
    'ee': 'Estonia',        'eg': 'Egypt',
    'es': 'Spain',          'fi': 'Finland',
    'fr': 'France',         'ge': 'Georgia',
    'gl': 'Greenland',      'gr': 'Greece',
    'gt': 'Guatemala',      'hk': 'Hong Kong',
    'hn': 'Honduras',       'hr': 'Croatia',
    'hu': 'Hungary',        'id': 'Indonesia',
    'ie': 'Ireland',        'il': 'Israel',
    'in': 'India',          'iq': 'Iraq',
    'is': 'Iceland',        'it': 'Italy',
    'jm': 'Jamaica',        'jo': 'Jordan',
    'jp': 'Japan',          'ke': 'Kenya',
    'kg': 'Kyrgyzstan',     'kh': 'Cambodia',
    'kr': 'South Korea',    'kz': 'Kazakhstan',
    'la': 'Laos',           'lk': 'Sri Lanka',
    'lt': 'Lithuania',      'lu': 'Luxembourg',
    'lv': 'Latvia',         'ma': 'Morocco',
    'md': 'Moldova',        'me': 'Montenegro',
    'mk': 'North Macedonia', 'mm': 'Myanmar',
    'mn': 'Mongolia',       'mo': 'Macau',
    'mt': 'Malta',          'mx': 'Mexico',
    'my': 'Malaysia',       'ng': 'Nigeria',
    'nl': 'Netherlands',    'no': 'Norway',
    'np': 'Nepal',          'nz': 'New Zealand',
    'pa': 'Panama',         'pe': 'Peru',
    'ph': 'Philippines',    'pk': 'Pakistan',
    'pl': 'Poland',         'pr': 'Puerto Rico',
    'pt': 'Portugal',       'py': 'Paraguay',
    'qa': 'Qatar',          'ro': 'Romania',
    'rs': 'Serbia',         'ru': 'Russia',
    'sa': 'Saudi Arabia',   'se': 'Sweden',
    'sg': 'Singapore',      'si': 'Slovenia',
    'sk': 'Slovakia',       'sv': 'El Salvador',
    'th': 'Thailand',       'tn': 'Tunisia',
    'tr': 'Turkey',         'tw': 'Taiwan',
    'ua': 'Ukraine',        'uk': 'United Kingdom',
    'gb': 'United Kingdom', 'us': 'United States',
    'uy': 'Uruguay',        'uz': 'Uzbekistan',
    've': 'Venezuela',      'vn': 'Vietnam',
    'za': 'South Africa',
}

# The provider's three-letter city codes. Only the ones its servers actually
# use are here; anything else shows as the raw code, which is at least true.
CITIES = {
    'adl': 'Adelaide',      'akl': 'Auckland',      'ams': 'Amsterdam',
    'anr': 'Antwerp',       'asu': 'Asuncion',      'ath': 'Athens',
    'bak': 'Baku',          'beg': 'Belgrade',      'ber': 'Berlin',
    'blp': 'Belmopan',      'bne': 'Brisbane',      'bod': 'Bordeaux',
    'bru': 'Brussels',      'bts': 'Bratislava',    'buc': 'Bucharest',
    'bud': 'Budapest',      'bwn': 'Bandar Seri Begawan',
    'cai': 'Cairo',         'cmb': 'Colombo',       'cph': 'Copenhagen',
    'chi': 'Chisinau',      'dac': 'Dhaka',         'dub': 'Dublin',
    'edi': 'Edinburgh',     'evn': 'Yerevan',       'fra': 'Frankfurt',
    'gdn': 'Gdansk',        'gla': 'Glasgow',       'goh': 'Nuuk',
    'hel': 'Helsinki',      'hkg': 'Hong Kong',     'jak': 'Jakarta',
    'ktm': 'Kathmandu',     'kul': 'Kuala Lumpur',  'lag': 'Lagos',
    'lis': 'Lisbon',        'lju': 'Ljubljana',     'lon': 'London',
    'leu': 'Les Escaldes',  'mad': 'Madrid',        'man': 'Manchester',
    'mel': 'Melbourne',     'mfm': 'Macau',         'mil': 'Milan',
    'mla': 'Valletta',      'mon': 'Montreal',      'mrs': 'Marseille',
    'mum': 'Mumbai',        'nas': 'Nassau',        'nic': 'Nicosia',
    'nyt': 'Naypyidaw',     'opo': 'Porto',         'osl': 'Oslo',
    'pac': 'Panama City',   'pbh': 'Thimphu',       'per': 'Perth',
    'pnh': 'Phnom Penh',    'prg': 'Prague',        'qro': 'Queretaro',
    'rab': 'Rabat',         'rig': 'Riga',          'rkv': 'Reykjavik',
    'rom': 'Rome',          'ruh': 'Riyadh',        'sao': 'Sao Paulo',
    'seo': 'Seoul',         'sjj': 'Sarajevo',      'sjn': 'San Jose',
    'sju': 'San Juan',      'skp': 'Skopje',        'sng': 'Singapore',
    'sof': 'Sofia',         'sre': 'Sucre',         'ste': 'Steinsel',
    'sto': 'Stockholm',     'syd': 'Sydney',        'tai': 'Taipei',
    'tbs': 'Tbilisi',       'tia': 'Tirana',        'tll': 'Tallinn',
    'tok': 'Tokyo',         'uio': 'Quito',         'uln': 'Ulaanbaatar',
    'vlc': 'Valencia',      'vno': 'Vilnius',       'vte': 'Vientiane',
    'waw': 'Warsaw',        'zur': 'Zurich',
}


def country_name(code):
    return NAMES.get((code or '').lower(), (code or '').upper())


def city_name(code):
    return CITIES.get((code or '').lower(), (code or '').upper())


# What people actually type. "swiss" does not fuzzy-match "Switzerland" and
# "holland" does not match "Netherlands" at any threshold worth having, so
# they are given rather than guessed at.
ALIASES = {
    'ae': 'uae emirates dubai',      'cz': 'czech republic',
    'gb': 'uk britain england',      'uk': 'uk britain england gb',
    'kr': 'korea',                   'mk': 'macedonia',
    'nl': 'holland dutch',           'ch': 'swiss',
    'us': 'usa america united states', 'za': 'rsa',
    'ba': 'bosnia',                  'do': 'dominican',
    'hk': 'hongkong',                'nz': 'newzealand',
    'sa': 'ksa',                     'tw': 'formosa',
    'ru': 'russian federation',      'ir': 'persia',
    'mm': 'burma',                   'tr': 'turkiye',
}


def aliases(code):
    return ALIASES.get((code or '').lower(), '')
