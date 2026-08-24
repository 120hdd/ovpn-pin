"""Two-letter codes to Persian country names.

The codes come out of the config filenames, which follow the provider's
naming rather than ISO in one place worth knowing about: the United Kingdom
is `uk`, not `gb`. Anything not listed falls back to the code in capitals,
which is wrong but never misleading - better than guessing at a country and
labelling a server with the wrong one.

No flags. The owner's rules put emoji out of bounds as interface furniture,
and a flag beside a country name is decoration standing in for a label that
is already there.
"""

NAMES = {
    'ad': 'آندورا',        'ae': 'امارات',        'af': 'افغانستان',
    'al': 'آلبانی',        'am': 'ارمنستان',      'ar': 'آرژانتین',
    'at': 'اتریش',         'au': 'استرالیا',      'az': 'آذربایجان',
    'ba': 'بوسنی',         'bd': 'بنگلادش',       'be': 'بلژیک',
    'bg': 'بلغارستان',     'bh': 'بحرین',         'bn': 'برونئی',
    'bo': 'بولیوی',        'br': 'برزیل',         'bs': 'باهاما',
    'bt': 'بوتان',         'by': 'بلاروس',        'bz': 'بلیز',
    'ca': 'کانادا',        'ch': 'سوئیس',         'cl': 'شیلی',
    'cn': 'چین',           'co': 'کلمبیا',        'cr': 'کاستاریکا',
    'cy': 'قبرس',          'cz': 'چک',            'de': 'آلمان',
    'dk': 'دانمارک',       'do': 'دومینیکن',      'dz': 'الجزایر',
    'ec': 'اکوادور',       'ee': 'استونی',        'eg': 'مصر',
    'es': 'اسپانیا',       'fi': 'فنلاند',        'fr': 'فرانسه',
    'ge': 'گرجستان',       'gl': 'گرینلند',       'gr': 'یونان',
    'gt': 'گواتمالا',      'hk': 'هنگ‌کنگ',        'hn': 'هندوراس',
    'hr': 'کرواسی',        'hu': 'مجارستان',      'id': 'اندونزی',
    'ie': 'ایرلند',        'il': 'اسرائیل',       'in': 'هند',
    'iq': 'عراق',          'is': 'ایسلند',        'it': 'ایتالیا',
    'jm': 'جامائیکا',      'jo': 'اردن',          'jp': 'ژاپن',
    'ke': 'کنیا',          'kg': 'قرقیزستان',     'kh': 'کامبوج',
    'kr': 'کره جنوبی',     'kz': 'قزاقستان',      'la': 'لائوس',
    'lk': 'سری‌لانکا',      'lt': 'لیتوانی',       'lu': 'لوکزامبورگ',
    'lv': 'لتونی',         'ma': 'مراکش',         'md': 'مولداوی',
    'me': 'مونته‌نگرو',     'mk': 'مقدونیه شمالی', 'mm': 'میانمار',
    'mn': 'مغولستان',      'mo': 'ماکائو',        'mt': 'مالت',
    'mx': 'مکزیک',         'my': 'مالزی',         'ng': 'نیجریه',
    'nl': 'هلند',          'no': 'نروژ',          'np': 'نپال',
    'nz': 'نیوزیلند',      'pa': 'پاناما',        'pe': 'پرو',
    'ph': 'فیلیپین',       'pk': 'پاکستان',       'pl': 'لهستان',
    'pr': 'پورتوریکو',     'pt': 'پرتغال',        'py': 'پاراگوئه',
    'qa': 'قطر',           'ro': 'رومانی',        'rs': 'صربستان',
    'ru': 'روسیه',         'sa': 'عربستان',       'se': 'سوئد',
    'sg': 'سنگاپور',       'si': 'اسلوونی',       'sk': 'اسلواکی',
    'sv': 'السالوادور',    'th': 'تایلند',        'tn': 'تونس',
    'tr': 'ترکیه',         'tw': 'تایوان',        'ua': 'اوکراین',
    'uk': 'بریتانیا',      'gb': 'بریتانیا',      'us': 'آمریکا',
    'uy': 'اروگوئه',       'uz': 'ازبکستان',      've': 'ونزوئلا',
    'vn': 'ویتنام',        'za': 'آفریقای جنوبی',
}


def country_name(code):
    return NAMES.get((code or '').lower(), (code or '').upper())
