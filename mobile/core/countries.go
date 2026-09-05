// Code generated from app/countries.py by tools/gen-countries.py.
// DO NOT EDIT - regenerate instead:
//
//	python mobile/tools/gen-countries.py

package core

import "strings"

// One country, one code, whatever the provider calls it. Surfshark's
// configs say `uk` and Windscribe's say `gb`, and with both switched on
// the United Kingdom appeared twice in the list - two rows, two flags'
// worth of servers split between them, and no way to tell from either
// that the other existed.
var canonical = map[string]string{
	"gb": "uk",
}

// Two-letter codes to country names. The codes come from the provider's
// config filenames rather than ISO, which differ in one place worth
// knowing: the United Kingdom is `uk`, not `gb`.
var countryNames = map[string]string{
	"ad": "Andorra",
	"ae": "United Arab Emirates",
	"af": "Afghanistan",
	"al": "Albania",
	"am": "Armenia",
	"aq": "Antarctica",
	"ar": "Argentina",
	"at": "Austria",
	"au": "Australia",
	"az": "Azerbaijan",
	"ba": "Bosnia and Herzegovina",
	"bd": "Bangladesh",
	"be": "Belgium",
	"bg": "Bulgaria",
	"bh": "Bahrain",
	"bn": "Brunei",
	"bo": "Bolivia",
	"br": "Brazil",
	"bs": "Bahamas",
	"bt": "Bhutan",
	"by": "Belarus",
	"bz": "Belize",
	"ca": "Canada",
	"ch": "Switzerland",
	"cl": "Chile",
	"cn": "China",
	"co": "Colombia",
	"cr": "Costa Rica",
	"cy": "Cyprus",
	"cz": "Czechia",
	"de": "Germany",
	"dk": "Denmark",
	"do": "Dominican Republic",
	"dz": "Algeria",
	"ec": "Ecuador",
	"ee": "Estonia",
	"eg": "Egypt",
	"es": "Spain",
	"fi": "Finland",
	"fr": "France",
	"gb": "United Kingdom",
	"ge": "Georgia",
	"gl": "Greenland",
	"gr": "Greece",
	"gt": "Guatemala",
	"hk": "Hong Kong",
	"hn": "Honduras",
	"hr": "Croatia",
	"hu": "Hungary",
	"id": "Indonesia",
	"ie": "Ireland",
	"il": "Israel",
	"in": "India",
	"iq": "Iraq",
	"is": "Iceland",
	"it": "Italy",
	"jm": "Jamaica",
	"jo": "Jordan",
	"jp": "Japan",
	"ke": "Kenya",
	"kg": "Kyrgyzstan",
	"kh": "Cambodia",
	"kr": "South Korea",
	"kz": "Kazakhstan",
	"la": "Laos",
	"lk": "Sri Lanka",
	"lt": "Lithuania",
	"lu": "Luxembourg",
	"lv": "Latvia",
	"ma": "Morocco",
	"md": "Moldova",
	"me": "Montenegro",
	"mk": "North Macedonia",
	"mm": "Myanmar",
	"mn": "Mongolia",
	"mo": "Macau",
	"mt": "Malta",
	"mx": "Mexico",
	"my": "Malaysia",
	"ng": "Nigeria",
	"nl": "Netherlands",
	"no": "Norway",
	"np": "Nepal",
	"nz": "New Zealand",
	"pa": "Panama",
	"pe": "Peru",
	"ph": "Philippines",
	"pk": "Pakistan",
	"pl": "Poland",
	"pr": "Puerto Rico",
	"pt": "Portugal",
	"py": "Paraguay",
	"qa": "Qatar",
	"ro": "Romania",
	"rs": "Serbia",
	"ru": "Russia",
	"sa": "Saudi Arabia",
	"se": "Sweden",
	"sg": "Singapore",
	"si": "Slovenia",
	"sk": "Slovakia",
	"sv": "El Salvador",
	"th": "Thailand",
	"tn": "Tunisia",
	"tr": "Turkey",
	"tw": "Taiwan",
	"ua": "Ukraine",
	"uk": "United Kingdom",
	"us": "United States",
	"uy": "Uruguay",
	"uz": "Uzbekistan",
	"ve": "Venezuela",
	"vn": "Vietnam",
	"za": "South Africa",
}

// Three-letter city codes, as they appear in the filenames.
var cityNames = map[string]string{
	"adl": "Adelaide",
	"akl": "Auckland",
	"ams": "Amsterdam",
	"anr": "Antwerp",
	"asu": "Asuncion",
	"ath": "Athens",
	"bak": "Baku",
	"beg": "Belgrade",
	"ber": "Berlin",
	"blp": "Belmopan",
	"bne": "Brisbane",
	"bod": "Bordeaux",
	"bru": "Brussels",
	"bts": "Bratislava",
	"buc": "Bucharest",
	"bud": "Budapest",
	"bwn": "Bandar Seri Begawan",
	"cai": "Cairo",
	"chi": "Chisinau",
	"cmb": "Colombo",
	"cph": "Copenhagen",
	"dac": "Dhaka",
	"dub": "Dublin",
	"edi": "Edinburgh",
	"evn": "Yerevan",
	"fra": "Frankfurt",
	"gdn": "Gdansk",
	"gla": "Glasgow",
	"goh": "Nuuk",
	"hel": "Helsinki",
	"hkg": "Hong Kong",
	"jak": "Jakarta",
	"ktm": "Kathmandu",
	"kul": "Kuala Lumpur",
	"lag": "Lagos",
	"leu": "Les Escaldes",
	"lis": "Lisbon",
	"lju": "Ljubljana",
	"lon": "London",
	"mad": "Madrid",
	"man": "Manchester",
	"mel": "Melbourne",
	"mfm": "Macau",
	"mil": "Milan",
	"mla": "Valletta",
	"mon": "Montreal",
	"mrs": "Marseille",
	"mum": "Mumbai",
	"nas": "Nassau",
	"nic": "Nicosia",
	"nyt": "Naypyidaw",
	"opo": "Porto",
	"osl": "Oslo",
	"pac": "Panama City",
	"pbh": "Thimphu",
	"per": "Perth",
	"pnh": "Phnom Penh",
	"prg": "Prague",
	"qro": "Queretaro",
	"rab": "Rabat",
	"rig": "Riga",
	"rkv": "Reykjavik",
	"rom": "Rome",
	"ruh": "Riyadh",
	"sao": "Sao Paulo",
	"seo": "Seoul",
	"sjj": "Sarajevo",
	"sjn": "San Jose",
	"sju": "San Juan",
	"skp": "Skopje",
	"sng": "Singapore",
	"sof": "Sofia",
	"sre": "Sucre",
	"ste": "Steinsel",
	"sto": "Stockholm",
	"syd": "Sydney",
	"tai": "Taipei",
	"tbs": "Tbilisi",
	"tia": "Tirana",
	"tll": "Tallinn",
	"tok": "Tokyo",
	"uio": "Quito",
	"uln": "Ulaanbaatar",
	"vlc": "Valencia",
	"vno": "Vilnius",
	"vte": "Vientiane",
	"waw": "Warsaw",
	"zur": "Zurich",
}

// Other things a person might type for the same place.
var countryAliases = map[string][]string{
	"ae": {"uae emirates dubai"},
	"ba": {"bosnia"},
	"ch": {"swiss"},
	"cz": {"czech republic"},
	"do": {"dominican"},
	"gb": {"uk britain england"},
	"hk": {"hongkong"},
	"ir": {"persia"},
	"kr": {"korea"},
	"mk": {"macedonia"},
	"mm": {"burma"},
	"nl": {"holland dutch"},
	"nz": {"newzealand"},
	"ru": {"russian federation"},
	"sa": {"ksa"},
	"tr": {"turkiye"},
	"tw": {"formosa"},
	"uk": {"uk britain england gb"},
	"us": {"usa america united states"},
	"za": {"rsa"},
}

// Canon folds a provider's spelling into the one this app files things under.
func Canon(code string) string {
	code = strings.ToLower(strings.TrimSpace(code))
	if to, ok := canonical[code]; ok {
		return to
	}
	return code
}

// CountryName is the country, or the code in capitals when it is not listed.
// Wrong but never misleading, which is the right way round - inventing a
// country name for a server would put a claim on screen that nothing checked.
func CountryName(code string) string {
	if n, ok := countryNames[Canon(code)]; ok {
		return n
	}
	return strings.ToUpper(code)
}

// CityName is the city behind a three-letter code, or the code in capitals.
func CityName(code string) string {
	if n, ok := cityNames[strings.ToLower(code)]; ok {
		return n
	}
	return strings.ToUpper(code)
}

// Aliases are the other things a person might type for the same place.
func Aliases(code string) []string {
	return countryAliases[Canon(code)]
}
