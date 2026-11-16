"""Job-posting slot filling: free-text extraction, edits, questions, description and requirements.

The draft lives in the backend (``draft = {"kind": "job_posting", "fields": {...}, "pending": ..., "asked": [...],
"stage": "collecting|review", "inferred": [...]}``). Every user message is parsed for as many fields as it names;
the planner asks only for what is still missing (one or two fields at a time), then builds the description and the
requirements (via the existing requirement compiler, rules engine) and proposes ``create_job`` with a full preview.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any, Callable

from app.assistant.text import NUMBER_WORD_RE, parse_date, word_number
from app.compile.requirements import _warnings as wording_warnings
from app.compile.requirements import compile_rules
from app.models.authoring import CompileRequest
from app.taxonomy.taxonomy import get_taxonomy

JOB_TYPES = {"FULL_TIME": "Full-time", "PART_TIME": "Part-time", "CONTRACT": "Contract", "INTERNSHIP": "Internship"}
LEVELS = {"ENTRY": "Entry level", "MID": "Mid level", "SENIOR": "Senior", "LEAD": "Lead", "EXECUTIVE": "Executive"}
REQUIRED_ORDER = ("title", "location", "jobType", "openings", "experienceLevel", "department", "deadline")
MAX_SKILLS = 25
MAX_TEXT = 4000

# --------------------------------------------------------------------------------------------- places
_CITY_ALIASES: dict[str, str] = {}
for canonical, aliases in {
    "Bengaluru": ("bengaluru", "bangalore", "blr", "bengalooru"), "Mumbai": ("mumbai", "bombay"),
    "Navi Mumbai": ("navi mumbai",), "Thane": ("thane",), "Pune": ("pune", "poona"),
    "Hyderabad": ("hyderabad", "hyd", "secunderabad"), "Chennai": ("chennai", "madras"),
    "Kolkata": ("kolkata", "calcutta"), "Delhi NCR": ("delhi ncr", "ncr"), "New Delhi": ("new delhi", "delhi"),
    "Gurugram": ("gurugram", "gurgaon", "ggn"), "Noida": ("noida", "greater noida"), "Faridabad": ("faridabad",),
    "Ghaziabad": ("ghaziabad",), "Ahmedabad": ("ahmedabad", "amdavad"), "Gandhinagar": ("gandhinagar",),
    "Surat": ("surat",), "Vadodara": ("vadodara", "baroda"), "Jaipur": ("jaipur",), "Udaipur": ("udaipur",),
    "Jodhpur": ("jodhpur",), "Kochi": ("kochi", "cochin", "ernakulam"), "Thiruvananthapuram": ("thiruvananthapuram", "trivandrum"),
    "Coimbatore": ("coimbatore",), "Madurai": ("madurai",), "Tiruchirappalli": ("tiruchirappalli", "trichy"),
    "Mysuru": ("mysuru", "mysore"), "Mangaluru": ("mangaluru", "mangalore"), "Hubballi": ("hubballi", "hubli"),
    "Belagavi": ("belagavi", "belgaum"), "Indore": ("indore",), "Bhopal": ("bhopal",), "Nagpur": ("nagpur",),
    "Nashik": ("nashik", "nasik"), "Aurangabad": ("aurangabad",), "Lucknow": ("lucknow",), "Kanpur": ("kanpur",),
    "Chandigarh": ("chandigarh",), "Mohali": ("mohali",), "Ludhiana": ("ludhiana",), "Amritsar": ("amritsar",),
    "Dehradun": ("dehradun",), "Bhubaneswar": ("bhubaneswar", "bhubaneshwar"), "Cuttack": ("cuttack",),
    "Visakhapatnam": ("visakhapatnam", "vizag"), "Vijayawada": ("vijayawada",), "Warangal": ("warangal",),
    "Patna": ("patna",), "Ranchi": ("ranchi",), "Jamshedpur": ("jamshedpur",), "Raipur": ("raipur",),
    "Guwahati": ("guwahati",), "Goa": ("goa", "panaji"), "Puducherry": ("puducherry", "pondicherry"),
    "Srinagar": ("srinagar",), "Jammu": ("jammu",), "Shimla": ("shimla",), "Varanasi": ("varanasi",),
    "Agra": ("agra",), "Prayagraj": ("prayagraj", "allahabad"), "Rajkot": ("rajkot",),
    "Singapore": ("singapore",), "Dubai": ("dubai",), "London": ("london",), "New York": ("new york",),
    "San Francisco": ("san francisco",), "Berlin": ("berlin",), "Toronto": ("toronto",), "Sydney": ("sydney",),
}.items():
    for alias in aliases:
        _CITY_ALIASES[alias] = canonical
_CITY_RE = re.compile(r"\b(" + "|".join(sorted((re.escape(alias) for alias in _CITY_ALIASES), key=len, reverse=True)) + r")\b", re.I)
_REMOTE_RE = re.compile(r"\b(?:fully\s+)?remote\b|\bwork from (?:home|anywhere)\b|\bwfh\b|\banywhere in india\b|\bdistributed\b")
_HYBRID_RE = re.compile(r"\bhybrid\b")
_ONSITE_RE = re.compile(r"\bon[- ]?site\b|\bin[- ]office\b|\bwork from office\b|\bwfo\b|\bin person\b")

# --------------------------------------------------------------------------------------------- types/levels
_JOB_TYPE_RES = (
    ("INTERNSHIP", re.compile(r"\binternships?\b|\bintern\b|\binterns\b|\btrainee\b|\bapprentice\w*\b")),
    ("PART_TIME", re.compile(r"\bpart[- ]?time\b|\bpt\b")),
    ("CONTRACT", re.compile(r"\bcontract(?:ual|or|ors)?\b|\bfreelanc\w*\b|\btemporary\b|\btemp\b|\bc2h\b|\bfixed[- ]term\b|\bconsultant basis\b")),
    ("FULL_TIME", re.compile(r"\bfull[- ]?time\b|\bft\b|\bfte\b|\bpermanent\b|\bregular\b|\bon[- ]roll\b")),
)
_LEVEL_RES = (
    ("EXECUTIVE", re.compile(r"\bhead of\b|\bdirector\b|\bvp\b|\bvice president\b|\bchief\b|\bc[tefo]o\b|\bexecutive[- ]level\b|\bcxo\b")),
    ("LEAD", re.compile(r"\blead\b|\bprincipal\b|\bstaff (?:engineer|scientist|designer)\b|\barchitect\b|\bteam lead\b|\blead[- ]level\b")),
    ("SENIOR", re.compile(r"\bsenior\b|\bsr\.?(?=\s)|\bsenior[- ]level\b")),
    ("MID", re.compile(r"\bmid(?:[- ]?level|[- ]senior)?\b|\bintermediate\b|\bexperienced\b|\bmiddle\b")),
    ("ENTRY", re.compile(r"\bentry(?:[- ]level)?\b|\bjunior\b|\bjr\.?(?=\s)|\bfreshers?\b|\bgraduate trainee\b|\bearly[- ]career\b|"
                         r"\btrainee\b|\binterns?\b|\binternship\b|\bbeginner\b")),
)

# --------------------------------------------------------------------------------------------- role families
@dataclass(frozen=True)
class RoleFamily:
    pattern: re.Pattern[str]
    department: str
    noun: str
    responsibilities: tuple[str, ...]


def _family(pattern: str, department: str, noun: str, *responsibilities: str) -> RoleFamily:
    return RoleFamily(re.compile(pattern, re.I), department, noun, responsibilities)


FAMILIES: tuple[RoleFamily, ...] = (
    _family(r"\bdata scien|\bmachine learning\b|\bml engineer\b|\bai engineer\b|\bnlp\b", "Data Science", "data science",
            "Frame business questions as measurable modelling problems and agree success metrics up front.",
            "Build, evaluate and document models, and explain their limits to stakeholders.",
            "Work with engineering to put models into production and monitor them.",
            "Keep data handling within privacy and fairness guidelines."),
    _family(r"\bdata engineer|\betl\b|\bdata platform\b", "Data Engineering", "data engineering",
            "Design and maintain reliable data pipelines and models.", "Monitor data quality and fix issues at the source.",
            "Work with analysts and scientists to make data easy to use.", "Document datasets, lineage and access rules."),
    _family(r"\bdata analy|\bbusiness analy|\bbi (?:analyst|developer)\b|\banalytics\b|\bmis (?:executive|analyst)\b|\breporting analyst\b",
            "Analytics", "analytics",
            "Turn business questions into clear analyses, dashboards and recommendations.",
            "Write and maintain reliable queries and data models.",
            "Present findings to stakeholders and track the impact of decisions.",
            "Improve data quality and document metrics definitions."),
    _family(r"\bdevops\b|\bsre\b|\bsite reliability\b|\bcloud engineer\b|\bplatform engineer\b|\binfrastructure\b", "Engineering",
            "infrastructure",
            "Build and run reliable cloud infrastructure and deployment pipelines.",
            "Improve monitoring, alerting and incident response.", "Automate repetitive operational work.",
            "Work with developers on performance, security and cost."),
    _family(r"\bqa\b|\btest(?:er|ing)?\b|\bquality assurance\b|\bsdet\b", "Quality Assurance", "quality assurance",
            "Plan and run manual and automated tests for new features.", "Report defects clearly and verify fixes.",
            "Maintain and extend the automated test suite.", "Help the team improve release quality."),
    _family(r"\bfront[- ]?end\b|\breact\b|\bangular\b|\bvue\b|\bui developer\b|\bweb developer\b", "Engineering", "front-end development",
            "Build accessible, responsive user interfaces.", "Work with designers and back-end engineers to ship features.",
            "Write tests and review code.", "Improve performance and front-end tooling."),
    _family(r"\bmobile\b|\bandroid\b|\bios\b|\bflutter\b|\breact native\b", "Engineering", "mobile development",
            "Build and release features for our mobile apps.", "Keep the apps fast, stable and accessible.",
            "Write tests and review code.", "Work with design and back-end teams on new features."),
    _family(r"\bback[- ]?end\b|\bjava\b|\bpython developer\b|\bnode\b|\bgolang\b|\bapi\b|\b\.net\b|\bphp\b", "Engineering",
            "back-end development",
            "Design, build and maintain services and APIs.", "Write tests, review code and improve reliability.",
            "Work with product and front-end engineers to ship features.", "Monitor and improve performance in production."),
    _family(r"\bsoftware\b|\bdeveloper\b|\bengineer\b|\bprogrammer\b|\bfull[- ]?stack\b|\bsde\b", "Engineering", "software development",
            "Design, build and maintain features end to end.", "Write tests and review code.",
            "Work with product and design to ship improvements.", "Keep systems reliable and well documented."),
    _family(r"\bdesigner\b|\bui/ux\b|\bux\b|\bui\b|\bproduct design\b|\bgraphic\b|\bvisual design\b", "Design", "design",
            "Design user flows, wireframes and high-fidelity interfaces.", "Run or support user research and usability tests.",
            "Maintain and extend the design system.", "Work closely with product and engineering through delivery."),
    _family(r"\bproduct manager\b|\bproduct owner\b|\bpm\b", "Product", "product management",
            "Own the roadmap for your area based on user and business evidence.",
            "Write clear problem statements and requirements.", "Work with design and engineering through delivery.",
            "Measure outcomes and decide what to do next."),
    _family(r"\bproject manager\b|\bprogram(?:me)? manager\b|\bscrum master\b|\bdelivery manager\b", "Project Management",
            "project management",
            "Plan and track delivery against scope, time and budget.", "Run planning, stand-ups and reviews.",
            "Manage risks and dependencies across teams.", "Keep stakeholders informed with clear status reports."),
    _family(r"\bsales\b|\bbusiness development\b|\bbde\b|\baccount (?:executive|manager)\b|\binside sales\b|\bpre[- ]?sales\b",
            "Sales", "sales",
            "Build and manage a pipeline of qualified prospects.", "Run discovery calls, demos and proposals.",
            "Negotiate and close deals, and keep the CRM up to date.", "Share customer feedback with product and marketing."),
    _family(r"\bmarketing\b|\bseo\b|\bcontent\b|\bsocial media\b|\bbrand\b|\bgrowth\b|\bcopywriter\b", "Marketing", "marketing",
            "Plan and run campaigns across the right channels.", "Create or brief clear, on-brand content.",
            "Track campaign performance and report on results.", "Work with sales on lead quality and messaging."),
    _family(r"\brecruiter\b|\btalent acquisition\b|\bhr\b|\bhuman resources\b|\bpeople (?:partner|operations)\b|\bpayroll\b",
            "Human Resources", "people operations",
            "Run hiring processes fairly and consistently with hiring managers.", "Keep candidates informed at every step.",
            "Maintain accurate HR records and reports.", "Help improve people policies and onboarding."),
    _family(r"\baccount(?:ant|s)\b|\bfinance\b|\bfinancial\b|\baudit\w*\b|\btax\b|\bchartered\b|\bca\b|\bbookkeep", "Finance", "finance",
            "Maintain accurate books and reconcile accounts.", "Prepare statutory filings and support audits.",
            "Produce monthly reports and variance analysis.", "Improve financial controls and processes."),
    _family(r"\bcustomer (?:support|success|service|care)\b|\bsupport (?:executive|engineer|specialist)\b|\bcall cent(?:re|er)\b|\bbpo\b|\bhelpdesk\b",
            "Customer Support", "customer support",
            "Resolve customer questions across phone, email and chat.", "Record issues clearly and escalate when needed.",
            "Meet response and resolution targets.", "Share recurring issues with the product team."),
    _family(r"\boperations\b|\blogistics\b|\bsupply chain\b|\bwarehouse\b|\bprocurement\b|\bpurchase\b", "Operations", "operations",
            "Run day-to-day operations against clear targets.", "Coordinate with vendors and internal teams.",
            "Track performance and fix bottlenecks.", "Keep processes documented and compliant."),
    _family(r"\bnurse\b|\bdoctor\b|\bpharmac\w*\b|\bmedical\b|\bclinical\b|\bphysiotherap\w*\b", "Healthcare", "healthcare",
            "Provide safe, high-quality care in line with clinical protocols.", "Keep accurate patient records.",
            "Work with the wider care team.", "Follow infection-control and safety procedures."),
    _family(r"\bteacher\b|\btrainer\b|\bfaculty\b|\btutor\b|\binstructor\b|\blecturer\b", "Education", "teaching",
            "Plan and deliver engaging lessons.", "Assess learners and give useful feedback.",
            "Adapt teaching to different needs.", "Work with colleagues and parents or sponsors."),
    _family(r"\blegal\b|\blawyer\b|\bcounsel\b|\bcompliance\b|\bparalegal\b", "Legal", "legal",
            "Draft and review contracts and policies.", "Advise teams on legal and compliance questions.",
            "Track regulatory changes.", "Manage external counsel where needed."),
    _family(r"\badmin\w*\b|\boffice\b|\breceptionist\b|\bexecutive assistant\b|\bfront desk\b", "Administration", "administration",
            "Keep the office and schedules running smoothly.", "Handle correspondence and records accurately.",
            "Coordinate with vendors and facilities.", "Support teams with day-to-day requests."),
)
_GENERIC_RESPONSIBILITIES = ("Deliver the core work of the role to an agreed standard.",
                             "Work with colleagues across teams to meet shared goals.",
                             "Keep work documented and stakeholders informed.",
                             "Suggest and make improvements to how the team works.")
_ACRONYMS = {"qa": "QA", "ui": "UI", "ux": "UX", "ui/ux": "UI/UX", "hr": "HR", "sql": "SQL", "aws": "AWS", "sre": "SRE",
             "devops": "DevOps", "ios": "iOS", "ml": "ML", "ai": "AI", "bi": "BI", "seo": "SEO", "it": "IT", "erp": "ERP",
             "sap": "SAP", "crm": "CRM", "mis": "MIS", "sde": "SDE", "sdet": "SDET", "bde": "BDE", "ca": "CA", "gcp": "GCP",
             "php": "PHP", "api": "API", "etl": "ETL", ".net": ".NET", "nlp": "NLP", "vp": "VP", "cto": "CTO", "cfo": "CFO",
             "ceo": "CEO", "coo": "COO", "hrbp": "HRBP", "pmo": "PMO", "b2b": "B2B", "b2c": "B2C", "ii": "II", "iii": "III"}
_SMALL = {"and", "of", "for", "the", "in", "to", "a", "an", "&", "with", "or"}
_ROLE_PLURALS = re.compile(r"(analyst|engineer|developer|designer|manager|executive|accountant|intern|lead|architect|scientist|"
                           r"specialist|associate|consultant|recruiter|tester|administrator|writer|officer|coordinator|"
                           r"representative|agent|nurse|teacher|technician|programmer|marketer|partner|trainee|assistant)s$", re.I)
_TITLE_STOP = (r"\s+(?:in|at|for|based|located|with|who|having|that|which|need|needs|needed|requiring|must|should|from|on|by|"
               r"and\s+(?:need|must|should|with)|full[- ]?time|part[- ]?time|remote|hybrid|on[- ]?site|contract(?:ual)?|internship|"
               r"job|jobs|role|roles|position|positions|opening|openings|posting|vacancy|vacancies|asap|urgently|immediately|"
               r"\d)\b|\s*[,;.!?()\[\]]|\s+[-–—:]\s+|$")
_TITLE_STARTS = (
    r"\b(?:job|posting|post|role|position|opening|vacancy|requisition|job description|jd|ad|advert)\s+(?:for|of)\s+",
    r"\b(?:hire|hiring|recruit|recruiting|looking for|need|want|require|requires|searching for)\s+(?:for\s+)?(?!to\s|a\s+job\b|an?\s+new\s+job\b)",
    r"\b(?:opening|position|role|job|title|designation)\s*(?:is|[:\-])\s*",
    r"^(?:the )?(?:role|title|position|job title) (?:is|would be|will be)\s+",
)
_COUNT_PREFIX = re.compile(rf"^(?:an?|one|the|some|a few|few|more|(\d{{1,3}}|{NUMBER_WORD_RE}))\s+", re.I)


def _title_case(phrase: str) -> str:
    words = []
    for index, word in enumerate(phrase.split()):
        lower = word.lower()
        if lower in _ACRONYMS:
            words.append(_ACRONYMS[lower])
        elif index and lower in _SMALL:
            words.append(lower)
        elif word.isupper() and len(word) <= 5:
            words.append(word)
        elif any(char.isupper() for char in word[1:]):
            words.append(word)  # keep "JavaScript", "DevOps"
        else:
            words.append(word[:1].upper() + word[1:])
    return " ".join(words)


def clean_title(phrase: str) -> tuple[str, int | None]:
    """Title-cased, singular title plus a head count written in front of it ("2 react developers")."""
    text = " ".join(phrase.split()).strip(" .,:;-'\"")
    count = None
    match = _COUNT_PREFIX.match(text)
    if match:
        if match.group(1):
            count = word_number(match.group(1))
        text = text[match.end():]
    text = re.sub(r"^(?:new|good|great|strong|talented|experienced)\s+", "", text, flags=re.I)
    text = _ROLE_PLURALS.sub(lambda m: m.group(1), text)
    text = re.sub(r"(?i)\bdevs?$", "Developer", text)  # "java dev" -> "Java Developer"
    return _title_case(text)[:120], count


_GENERIC_HEAD = re.compile(r"(?i)(fresher|resource|people|person|staff|candidate|trainee|hand|guy|folk|member|hire)s?")
_ROLE_NOUN_END = re.compile(r"(?i)\b(?:analyst|engineer|developer|designer|manager|executive|accountant|intern|lead|architect|scientist|"
                            r"specialist|associate|consultant|recruiter|tester|administrator|writer|officer|coordinator|"
                            r"representative|agent|nurse|teacher|technician|programmer|marketer|trainee|assistant|head|director)$")


def _generic_title(head: str, after: str) -> str | None:
    """'3 freshers for customer support' -> 'Customer Support Trainee'; '2 resources for SAP FICO' -> 'SAP FICO Consultant'."""
    match = re.match(r"\s+for\s+(?:the\s+|a\s+|an\s+|our\s+)?", after, re.I)
    if not match:
        return None
    rest = after[match.end():]
    stop = re.search(_TITLE_STOP, rest, re.I)
    area, _ = clean_title(rest[:stop.start() if stop else len(rest)])
    if not area or len(area.split()) > 5 or re.fullmatch(r"(?i)(?:a|an|the|it|this|that|our|my|us|me|job|team|company)", area):
        return None
    if _ROLE_NOUN_END.search(area):
        return area
    suffix = {"fresher": "Trainee", "trainee": "Trainee", "resource": "Consultant"}.get(head.lower().rstrip("s"), "Executive")
    return f"{area} {suffix}"


def find_title(raw: str) -> tuple[str | None, int | None]:
    text = " ".join(raw.split())
    for start in _TITLE_STARTS:
        for match in re.finditer(start, text, re.I):
            rest = text[match.end():]
            count_match = _COUNT_PREFIX.match(rest)
            offset = count_match.end() if count_match else 0
            stop = re.search(_TITLE_STOP, rest[offset:], re.I)
            candidate = rest[:offset + (stop.start() if stop else len(rest) - offset)]
            title, count = clean_title(candidate)
            if _GENERIC_HEAD.fullmatch(title):
                title = _generic_title(title, rest[len(candidate):]) or title
            if 1 <= len(title.split()) <= 7 and not re.fullmatch(r"(?i)(?:a|an|the|job|someone|people|candidates?|it|this|that|"
                                                                 r"to|help|more|new|urgently)", title):
                return title, count
    match = re.search(r"\b(?:post|create|add|open|draft|publish|raise)\s+(?:an?\s+|new\s+|one\s+)+(.{2,60}?)\s+"
                      r"(?:job|role|position|opening|posting|vacancy|requisition)\b", text, re.I)
    if match:
        title, count = clean_title(match.group(1))
        if title and not re.fullmatch(r"(?i)new|job", title):
            return title, count
    return None, None


def family_for(title: str | None) -> RoleFamily | None:
    if not title:
        return None
    return next((family for family in FAMILIES if family.pattern.search(title)), None)


def base_title(title: str) -> str:
    stripped = re.sub(r"(?i)^(?:senior|sr\.?|junior|jr\.?|lead|principal|staff|associate|trainee|head of|chief)\s+", "", title).strip()
    stripped = re.sub(r"(?i)\s+(?:I|II|III|IV|1|2|3)$", "", stripped)
    return stripped or title


# --------------------------------------------------------------------------------------------- extraction
_NICE_CUE = re.compile(r"\bnice[- ]to[- ]have\b|\bgood[- ]to[- ]have\b|\bpreferred\b|\bpreferably\b|\bbonus\b|\ba plus\b|\bplus point\b|"
                       r"\boptional(?:ly)?\b|\bideally\b|\bdesirable\b|\badded advantage\b|\badvantage\b|\bnot (?:a )?must\b|"
                       r"\bnot mandatory\b|\bnot required\b")
_KNOCKOUT_CUE = re.compile(r"\bmust(?:[- ]have)?\b|\bmandatory\b|\bnon[- ]negotiable\b|\brequired\b|\bessential\b|\bcompulsory\b|"
                           r"\bknock[- ]?out\b|\bgate\b")
_REMOVE_CUE = re.compile(r"\b(?:remove|drop|delete|no need (?:for|of)|don'?t need|do not need|without|take out|get rid of|exclude|"
                         r"strike|scrap|skip)\b")
_SKILL_CUE = re.compile(r"\bneed|\bskills?\b|\bwith\b|\bknowledge\b|\bproficien|\bexperience\b|\bexpertise\b|\bknow\b|\bfamiliar|"
                        r"\bhands[- ]on\b|\badd\b|\balso\b|\brequire|\bmust\b|\bnice\b|\bgood to\b|\bplus\b|\bstack\b|\btools?\b|"
                        r"\busing\b|\bin\b|\bstrong\b|\bremove\b|\bdrop\b|\bmake\b|\bmandatory\b|\boptional\b")
_LEVEL_WORD = re.compile(r"\b(expert|advanced|in[- ]depth|deep|strong|solid|good|working knowledge of|basic|basics of|familiarity with)\s+$", re.I)
_YEARS_RE = re.compile(rf"(?:\b(minimum|min\.?|at least|atleast)\s+(?:of\s+)?)?\b(\d{{1,2}}|{NUMBER_WORD_RE})\s*\+?\s*(?:(?:-|to|–)\s*(\d{{1,2}})\s*\+?\s*)?"
                       r"(?:years?|yrs?|yr)\b", re.I)
_OPENINGS_RES = (
    re.compile(rf"\b(\d{{1,3}}|{NUMBER_WORD_RE})\s+(?:openings?|positions?|vacanc(?:y|ies)|seats?|headcounts?|hires?|people|persons|"
               r"resources|nos\.?|numbers?|slots?)\b", re.I),
    re.compile(rf"\b(?:openings?|positions?|vacanc(?:y|ies)|headcount|no\.? of (?:openings|positions|hires)|number of (?:openings|positions|hires))"
               rf"\s*(?:to|=|:|as|is|are|of)?\s*(\d{{1,3}}|{NUMBER_WORD_RE})\b", re.I),
    re.compile(rf"\b(?:hire|hiring|recruit|need|want|looking for)\s+(\d{{1,3}}|{NUMBER_WORD_RE})\s+(?=[a-z])", re.I),
    re.compile(r"\bx\s?(\d{1,3})\b", re.I),
)
_DEADLINE_CUE = re.compile(r"\b(?:deadline|apply by|applications? (?:close|closes|closing|due|open till|open until)|closing date|"
                           r"last date|close (?:it |the job |applications )?(?:on|by|in)|closes (?:on|in)|closing (?:on|in|by)|until|till|by|before|due|"
                           r"expires?|expiry|ends?(?: on)?|valid till)\b", re.I)
_NO_DEADLINE = re.compile(r"\bno deadline\b|\bwithout (?:a )?deadline\b|\bno closing date\b|\buntil filled\b|\btill filled\b|"
                          r"\bopen[- ]ended\b|\bno end date\b|\bnone\b|\bskip\b|\bnot needed\b|\bno need\b|\bnot sure\b|\bno\b")
_SALARY_RE = re.compile(r"(?:₹|rs\.?|inr)?\s*(\d+(?:[.,]\d+)*)\s*(l|lpa|lakhs?|lacs?|k|cr|crores?)?\s*(?:-|to|–)\s*(?:₹|rs\.?|inr)?\s*"
                        r"(\d+(?:[.,]\d+)*)\s*(lpa|l\b|lakhs?|lacs?|k\b|cr\b|crores?|per annum|p\.?a\.?|ctc|per month|/month|pm\b|monthly)?", re.I)
_SALARY_SINGLE_RE = re.compile(r"\b(up ?to|upto|max(?:imum)?|around|about|~|from|starting(?: at)?|min(?:imum)?)\s*(?:of\s+)?(?:₹|rs\.?|inr)?\s*"
                               r"(\d+(?:[.,]\d+)*)\s*(lpa|l\b|lakhs?|lacs?|k\b|cr\b|crores?)", re.I)
_SALARY_CUE = re.compile(r"\bsalary\b|\bctc\b|\bpay\b|\blpa\b|\blakhs?\b|\blacs?\b|\bcompensation\b|\bpackage\b|₹|\binr\b|\brs\.?\s*\d|\bper month\b|\bstipend\b")
_UNITS = {"l": 100_000, "lpa": 100_000, "lakh": 100_000, "lakhs": 100_000, "lac": 100_000, "lacs": 100_000,
          "k": 1_000, "cr": 10_000_000, "crore": 10_000_000, "crores": 10_000_000}


def _amount(raw: str, unit: str | None) -> float:
    digits = raw.replace(",", "")
    value = float(digits) if digits.count(".") <= 1 else float(digits.replace(".", ""))
    return value * _UNITS.get((unit or "").lower().strip(), 1)


def _salary(text: str) -> tuple[int | None, int | None] | None:
    if not _SALARY_CUE.search(text):
        return None
    match = _SALARY_RE.search(text)
    if match and not re.match(r"\s*(?:years?|yrs?|openings?|positions?)", text[match.end():]):
        unit_low, unit_high = match.group(2), match.group(4)
        unit_high_key = (unit_high or "").lower()
        monthly = unit_high_key in {"per month", "/month", "pm", "monthly"} or bool(re.search(r"\bper month\b|\bmonthly\b|\bstipend\b", text, re.I))
        unit_value = unit_high if unit_high_key in _UNITS or unit_high_key.rstrip("s") in _UNITS else None
        low = _amount(match.group(1), unit_low or unit_value)
        high = _amount(match.group(3), unit_value or unit_low)
        if monthly:
            low, high = low * 12, high * 12
        if 0 < low <= high:
            return int(low), int(high)
    match = _SALARY_SINGLE_RE.search(text)
    if match:
        amount = int(_amount(match.group(2), match.group(3)))
        return (None, amount) if match.group(1).lower().replace(" ", "") in {"upto", "max", "maximum"} else (amount, None)
    return None


def _skill_clauses(text: str) -> list[tuple[str, str]]:
    """(clause, mode) with mode inherited along a sentence: must | knockout | nice | remove."""
    pieces: list[tuple[str, str]] = []
    for sentence in re.split(r"[.;\n!?]+|\bbut\b|\balso\b", text):
        carry = "must"
        for clause in re.split(r",|\band also\b|(?<!a )\bplus\b(?! point)", sentence):
            lowered = clause.lower()
            if _REMOVE_CUE.search(lowered):
                mode = "remove"
            elif _NICE_CUE.search(lowered):
                mode = "nice"
            elif _KNOCKOUT_CUE.search(lowered):
                mode = "knockout"
            elif re.search(r"\bneed|\brequire|\bskills?\b|\bknowledge\b|\bwith\b", lowered):
                mode = "must"
            else:
                mode = carry
            carry = mode if mode != "remove" else carry
            pieces.append((clause, mode))
    return pieces


def find_skills(text: str, *, force_list: bool = False) -> list[dict[str, Any]]:
    """Skills the text names, each with its mode, level word and years."""
    taxonomy = get_taxonomy()
    found: list[dict[str, Any]] = []
    for clause, mode in _skill_clauses(text):
        # A short comma-separated item ("..., Selenium, ...") is a list item; longer clauses need a skill cue.
        list_like = force_list or bool(_SKILL_CUE.search(clause.lower())) or 1 <= len(clause.split()) <= 3
        mentions = taxonomy.find_mentions(clause, list_context=list_like)
        years = _YEARS_RE.search(clause)
        for mention in mentions:
            level = _LEVEL_WORD.search(clause[:mention.start])
            entry = {"name": mention.skill.name, "mode": mode,
                     "level": level.group(1).lower() if level else None,
                     "years": (word_number(years.group(2)) if years and len(mentions) == 1 else None),
                     "hardYears": bool(years and years.group(1)) if len(mentions) == 1 else False}
            if all(item["name"] != entry["name"] for item in found):
                found.append(entry)
    return found


@dataclass
class Extraction:
    updates: dict[str, Any] = field(default_factory=dict)
    skills: list[dict[str, Any]] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


# A short answer that clearly fills another field ("mid", "full time", "no deadline") is never taken as a title,
# a city or a department just because that was the pending question.
_OTHER_FIELD_ANSWER = re.compile(r"^(?:entry|mid|middle|senior|junior|lead|fresher|freshers|executive|experienced)(?:[- ]?level)?$|"
                                 r"^(?:full|part)[- ]?time$|^contract(?:ual)?$|^internship$|^(?:yes|no|ok|okay|sure|skip|none|n/?a)$|"
                                 r"\bdeadline\b|\bclos(?:e|es|ing)\b|\buntil filled\b|\bsalary\b|\blpa\b|\bctc\b|\bopenings?\b|"
                                 r"\bpositions?\b|\byears?\b|\byrs?\b")


def _bare_answer(text: str) -> str:
    stripped = re.sub(r"(?i)^(?:it(?:'s| is| will be| should be)|it'?s|the (?:role|title|location|department|team) (?:is|will be)|"
                      r"we are|location is|based in|in|at|title is|department is|dept is|team is|call it|for)\s+", "", text.strip())
    return stripped.strip(" .!'\"")


def extract(raw: str, fields: dict[str, Any], pending: str | None, today: date) -> Extraction:
    """Fields the message names, interpreted against the pending question when it is a short answer."""
    text = " ".join((raw or "").split())[:MAX_TEXT]
    lowered = text.lower()
    result = Extraction()
    up = result.updates

    # ---- title
    title, head_count = find_title(text)
    rename = re.search(r"\b(?:change|set|update|rename|retitle)\s+(?:the\s+)?(?:job\s+)?title\s+(?:to|as)\s+(.+?)\s*$|"
                       r"\b(?:rename (?:it|the job|the role) to|call it|title should be|make the title)\s+(.+?)\s*$", text, re.I)
    if rename:
        title, head_count = clean_title(rename.group(1) or rename.group(2))
    elif pending == "title" and not title and not _OTHER_FIELD_ANSWER.search(_bare_answer(text).lower()):
        candidate = _bare_answer(text)
        candidate = re.split(_TITLE_STOP, candidate, maxsplit=1, flags=re.I)[0] if len(candidate.split()) > 6 else candidate
        title, head_count = clean_title(candidate)
    if title:
        up["title"] = title
    if head_count and head_count > 0:
        up["openings"] = head_count

    # ---- location and work mode
    cities = []
    for match in _CITY_RE.finditer(text):
        city = _CITY_ALIASES[match.group(1).lower()]
        if city not in cities:
            cities.append(city)
    if "Delhi NCR" in cities and "New Delhi" in cities:
        cities.remove("New Delhi")
    if _REMOTE_RE.search(lowered):
        up["workMode"] = "REMOTE"
    elif _HYBRID_RE.search(lowered):
        up["workMode"] = "HYBRID"
    elif _ONSITE_RE.search(lowered) or re.search(r"\b(?:not remote|no remote|from (?:the )?office)\b", lowered):
        up["workMode"] = "ONSITE"
    label = re.search(r"\b(?:location|based (?:in|out of)|city|office (?:in|at)|located (?:in|at))\s*(?:is|:|-|to)?\s*"
                      r"([A-Z][A-Za-z .'-]{1,40}?)(?=$|[,;.!]|\s+(?:and|with|for|full|part|\d))", text)
    if cities:
        up["city"] = " / ".join(cities[:3])
    elif label and not re.match(r"(?i)remote|hybrid|on-?site", label.group(1)):
        up["city"] = _title_case(label.group(1).strip())
    elif pending == "location" and "workMode" not in up:
        answer = _bare_answer(text)
        if 1 <= len(answer.split()) <= 4 and not re.search(r"\d", answer) and not _OTHER_FIELD_ANSWER.search(answer.lower()):
            up["city"] = _title_case(answer)
    if re.search(r"\b(?:make it|change it to|switch to|go)\s+(?:on[- ]?site|in[- ]office)\b", lowered):
        up["workMode"] = "ONSITE"

    # ---- job type and level
    for code, pattern in _JOB_TYPE_RES:
        if pattern.search(lowered):
            up["jobType"] = code
            break
    level_text = lowered
    for code, pattern in _LEVEL_RES:
        if pattern.search(level_text):
            if code == "LEAD" and re.search(r"\blead(?:s)?\s+(?:generation|management|qualification)\b", level_text):
                continue
            if code == "EXECUTIVE" and re.search(r"\b(?:sales|account|marketing|support|hr|customer|service) executive\b", level_text):
                continue
            up["experienceLevel"] = code
            break
    if pending == "experienceLevel" and "experienceLevel" not in up:
        answer = lowered.strip(" .")
        for word, code in (("entry", "ENTRY"), ("mid", "MID"), ("senior", "SENIOR"), ("lead", "LEAD"), ("exec", "EXECUTIVE")):
            if answer.startswith(word):
                up["experienceLevel"] = code

    # ---- openings
    for pattern in _OPENINGS_RES:
        match = pattern.search(text)
        if match:
            value = word_number(match.group(1))
            if value and 0 < value <= 500:
                up["openings"] = value
                break
    if "openings" not in up and pending in {"openings", "jobType"}:
        match = re.fullmatch(rf"(?:just |only )?(\d{{1,3}}|{NUMBER_WORD_RE})(?: (?:only|of them|people|openings?|positions?))?",
                             re.sub(r"(?i)^(?:full[- ]?time|part[- ]?time|contract|internship)[, ]*(?:and|with)?\s*", "",
                                    lowered.strip(" .")))
        if match and (word_number(match.group(1)) or 0) > 0:
            up["openings"] = word_number(match.group(1))

    # ---- years of experience (whole job)
    for match in _YEARS_RE.finditer(text):
        clause = text[max(0, match.start() - 40):match.end() + 40]
        mentions = get_taxonomy().find_mentions(text[match.start():match.end() + 30], list_context=True)
        if mentions and re.match(r"\s*(?:of\s+)?(?:experience\s+)?(?:in|with|of|using)\s", text[match.end():match.end() + 25]):
            continue  # "3 years of Python": attached to the skill
        if re.search(r"\bago\b|\bold\b|\bof age\b", clause[match.end() - max(0, match.start() - 40):][:15]):
            continue
        up["minYears"] = word_number(match.group(2))
        up["minYearsHard"] = bool(match.group(1))
        break

    # ---- deadline
    if _NO_DEADLINE.search(lowered) and (pending == "deadline" or re.search(r"\bdeadline\b|\bclosing\b|\bfilled\b", lowered)):
        if not parse_date(text, today):
            up["deadline"] = None
            up["noDeadline"] = True
    else:
        extend = re.search(rf"\b(?:extend|push|move|postpone)\b.*\bby\s+(\d{{1,3}}|{NUMBER_WORD_RE}|a|an)\s+(days?|weeks?|months?)\b", lowered)
        current = fields.get("deadline")
        if extend and current:
            count = 1 if extend.group(1) in {"a", "an"} else (word_number(extend.group(1)) or 0)
            base = date.fromisoformat(current)
            days = count * (30 if extend.group(2).startswith("month") else 7 if extend.group(2).startswith("week") else 1)
            up["deadline"] = (base + timedelta(days=days)).isoformat()
        elif pending == "deadline" or _DEADLINE_CUE.search(text):
            cue = _DEADLINE_CUE.search(text)
            scope = text[cue.start():] if cue and pending != "deadline" else text
            found = parse_date(scope, today)
            if found:
                up["deadline"] = found.isoformat()
                up["noDeadline"] = False

    # ---- salary
    if re.search(r"\b(?:remove|hide|no|don'?t show|without|skip)\s+(?:the\s+)?(?:salary|ctc|pay|compensation)\b", lowered):
        up["salaryMin"], up["salaryMax"] = None, None
    else:
        salary = _salary(text)
        if salary:
            up["salaryMin"], up["salaryMax"] = salary

    # ---- department
    department = re.search(r"\b(?:department|dept\.?|team|function)\s*(?:is|:|-|to|as|=|should be)\s*([A-Za-z&/ .'-]{2,60}?)\s*(?=$|[,;.!])", text, re.I) or \
        re.search(r"\b(?:in|for|under) (?:the |our )?([A-Z][A-Za-z&/ '-]{1,40}?) (?:department|dept|team)\b", text)
    if department:
        up["department"] = _title_case(department.group(1).strip())
    elif pending == "department":
        answer = _bare_answer(text)
        if 1 <= len(answer.split()) <= 5 and not _OTHER_FIELD_ANSWER.search(answer.lower()) and not parse_date(answer, today):
            up["department"] = _title_case(answer)

    # ---- summary / responsibilities
    summary = re.search(r"\b(?:summary|about the role|role summary|overview)\s*[:\-]\s*(.{10,1500})", text, re.I)
    if summary:
        up["summary"] = summary.group(1).strip()
    duties = re.search(r"\b(?:responsibilities|duties|what (?:they|you)(?:'ll| will) do|key tasks)\s*[:\-]\s*(.{5,2000}?)(?=\b(?:requirements|skills|"
                       r"summary|nice to have)\s*:|$)", raw or "", re.I | re.S)
    if duties:
        items = [item.strip(" -•*\t") for item in re.split(r"[\n;]|\s+-\s+|•", duties.group(1)) if len(item.strip(" -•*\t")) >= 4]
        if items:
            up["responsibilities"] = [item[:300] for item in items[:10]]

    # ---- extra requirement lines (degrees, certificates, languages) written in the message
    extras = []
    for sentence in re.split(r"[;\n]|,\s*(?=[A-Za-z])", text):
        if re.search(r"\b(?:degree|b\.?\s?tech|b\.?e\.?|bachelor'?s?|master'?s?|m\.?\s?tech|mba|mca|bca|b\.?com|m\.?com|ph\.?d|diploma|"
                     r"certifi\w+|licen[cs]e)\b", sentence, re.I) and len(sentence.split()) <= 25:
            extras.append(re.sub(r"(?i)^\s*(?:and\s+|need\s+|needs\s+|with\s+|also\s+|add\s+|should have\s+|must have\s+|a\s+)", "",
                                 sentence.strip(" .")))
    if extras:
        result.notes = [item[:200] for item in extras if item]

    # ---- skills (the title is blanked out so "React Developer" is not read twice)
    skill_text = text
    if title and up.get("title") == title:
        skill_text = re.sub(re.escape(title.split()[-1]), " ", skill_text, flags=re.I)
    result.skills = find_skills(skill_text, force_list=pending == "requirements")
    if title and up.get("title") == title and not fields.get("title"):
        for mention in get_taxonomy().find_mentions(title, list_context=True):
            if all(item["name"] != mention.skill.name for item in result.skills):
                result.skills.append({"name": mention.skill.name, "mode": "must", "level": None, "years": None,
                                      "hardYears": False})
    if pending == "requirements" and not result.skills:
        if re.search(r"\bskip\b|\bnone\b|\bno\b|\bnothing\b|\blater\b", lowered):
            up["skillsSkipped"] = True
        elif not result.notes:
            # Not in the taxonomy: keep the user's words as requirement lines (the compiler marks them for human review).
            items = [item.strip(" .") for item in re.split(r"[,;\n]|\band\b", text) if len(item.strip(" .")) >= 2]
            result.notes = [item[:200] for item in items[:10]]
    return result


def apply(fields: dict[str, Any], extraction: Extraction) -> tuple[dict[str, Any], list[str]]:
    """New fields after applying an extraction, plus a list of human-readable changes."""
    updated = dict(fields)
    changes: list[str] = []
    for key, value in extraction.updates.items():
        if updated.get(key) != value:
            updated[key] = value
            if key in {"title", "city", "workMode", "jobType", "experienceLevel", "openings", "deadline", "department",
                       "salaryMin", "salaryMax", "minYears", "summary", "responsibilities"}:
                changes.append(key)
    skills = [dict(item) for item in updated.get("skills", [])]
    for item in extraction.skills:
        existing = next((skill for skill in skills if skill["name"].lower() == item["name"].lower()), None)
        if item["mode"] == "remove":
            if existing:
                skills.remove(existing)
                changes.append(f"-{item['name']}")
            continue
        entry = {"name": item["name"], "mandatory": item["mode"] != "nice", "knockout": item["mode"] == "knockout",
                 "level": item.get("level"), "years": item.get("years"), "hardYears": item.get("hardYears", False)}
        if existing:
            if existing != {**existing, **{k: v for k, v in entry.items() if v is not None}}:
                existing.update({k: v for k, v in entry.items() if v is not None or k in {"mandatory", "knockout"}})
                changes.append(f"~{item['name']}")
        elif len(skills) < MAX_SKILLS:
            skills.append(entry)
            changes.append(f"+{item['name']}")
    updated["skills"] = skills
    if extraction.notes:
        notes = list(updated.get("extraRequirements", []))
        for note in extraction.notes:
            if note.lower() not in {existing.lower() for existing in notes}:
                notes.append(note)
                changes.append("requirement")
        updated["extraRequirements"] = notes[:15]
    updated["location"] = compose_location(updated)
    return updated, changes


def compose_location(fields: dict[str, Any]) -> str | None:
    mode, city = fields.get("workMode"), fields.get("city")
    if mode == "REMOTE":
        return "Remote"
    if not city:
        return None
    return f"{city} (Hybrid)" if mode == "HYBRID" else city


def infer(fields: dict[str, Any], inferred: list[str]) -> tuple[dict[str, Any], list[str]]:
    """Fill department and level from the title / years when the user did not say them (shown as assumptions)."""
    updated, inferred = dict(fields), list(inferred)
    family = family_for(updated.get("title"))
    if not updated.get("department") and family:
        updated["department"] = family.department
        inferred.append("department")
    if not updated.get("experienceLevel"):
        years = updated.get("minYears")
        if updated.get("jobType") == "INTERNSHIP":
            updated["experienceLevel"] = "ENTRY"
            inferred.append("experienceLevel")
        elif isinstance(years, int):
            updated["experienceLevel"] = "ENTRY" if years <= 1 else "MID" if years <= 4 else "SENIOR" if years <= 8 else "LEAD"
            inferred.append("experienceLevel")
    return updated, sorted(set(inferred))


def missing(fields: dict[str, Any]) -> list[str]:
    gaps = []
    for name in REQUIRED_ORDER:
        if name == "location":
            if not compose_location(fields):
                gaps.append(name)
        elif name == "deadline":
            if not fields.get("deadline") and not fields.get("noDeadline"):
                gaps.append(name)
        elif not fields.get(name):
            gaps.append(name)
    return gaps


def question_for(gaps: list[str], fields: dict[str, Any], today: date) -> tuple[str, str, list[str]]:
    """(text, field, options) asking for the next one or two missing fields."""
    first = gaps[0]
    title = fields.get("title") or "this role"
    if first == "title":
        return "What role are you hiring for? For example: 'Senior Data Analyst in Pune, full-time, 2 openings'.", "title", []
    if first == "location":
        if fields.get("workMode") == "HYBRID":
            return f"Which city is the hybrid {title} role based in?", "location", ["Bengaluru", "Pune", "Hyderabad", "Mumbai", "Delhi NCR"]
        return f"Where is the {title} role based? You can name a city or say remote.", "location", \
            ["Remote", "Bengaluru", "Pune", "Hyderabad", "Mumbai", "Delhi NCR", "Chennai"]
    if first == "jobType":
        if "openings" in gaps:
            return f"Is the {title} role full-time, part-time, contract or an internship, and how many openings are there?", \
                "jobType", list(JOB_TYPES.values())
        return f"Is the {title} role full-time, part-time, contract or an internship?", "jobType", list(JOB_TYPES.values())
    if first == "openings":
        return f"How many openings are there for {title}?", "openings", ["1", "2", "3", "5"]
    if first == "experienceLevel":
        return f"What experience level is the {title} role?", "experienceLevel", ["Entry", "Mid", "Senior", "Lead", "Executive"]
    if first == "department":
        return f"Which department or team is the {title} role in?", "department", \
            ["Engineering", "Analytics", "Sales", "Marketing", "Operations", "Finance", "Human Resources"]
    return (f"When should applications close? For example 'in 3 weeks' or '{(today + timedelta(days=30)).strftime('%d %b')}'. "
            "You can also say 'no deadline'."), "deadline", ["In 2 weeks", "In 30 days", "End of next month", "No deadline"]


# --------------------------------------------------------------------------------------------- preview
def _skill_line(skill: dict[str, Any]) -> str:
    level = (skill.get("level") or "").strip()
    name = f"{level[:1].upper()}{level[1:]} {skill['name']}" if level else skill["name"]
    if skill.get("years"):
        lead = "At least " if skill.get("hardYears") or skill.get("knockout") else ""
        return f"{lead}{skill['years']}{'' if lead else '+'} years of experience with {skill['name']}"
    return f"{name} is required" if skill.get("knockout") else name


def requirements_text(fields: dict[str, Any]) -> str:
    must, nice = [], []
    title = fields.get("title") or "the role"
    if fields.get("minYears"):
        lead = "At least " if fields.get("minYearsHard") else ""
        must.append(f"{lead}{fields['minYears']}{'' if lead else '+'} years of {base_title(title)} experience")
    for skill in fields.get("skills", []):
        (must if skill.get("mandatory", True) else nice).append(_skill_line(skill))
    must.extend(fields.get("extraRequirements", []))
    lines = []
    if must:
        lines.append("Requirements:")
        lines.extend(f"- {line}" for line in must)
    if nice:
        lines.append("Nice to have:")
        lines.extend(f"- {line}" for line in nice)
    return "\n".join(lines)


def template_prose(fields: dict[str, Any]) -> dict[str, Any]:
    title = fields.get("title") or "team member"
    family = family_for(title)
    department = fields.get("department")
    where = fields.get("location")
    place = " working remotely" if where == "Remote" else (f" in {where}" if where else "")
    team = f" {department}" if department else ""
    focus = f" focused on {family.noun}" if family else ""
    summary = (f"We are looking for a {title} to join our{team} team{place}. This is a "
               f"{JOB_TYPES.get(fields.get('jobType', ''), 'new').lower()} role{focus}, "
               f"with {_openings_phrase(fields.get('openings'))}.")
    responsibilities = list(fields.get("responsibilities") or (family.responsibilities if family else _GENERIC_RESPONSIBILITIES))
    return {"summary": fields.get("summary") or summary, "responsibilities": responsibilities}


def _openings_phrase(openings: Any) -> str:
    return "one opening" if openings in (None, 1) else f"{openings} openings"


def compose_description(fields: dict[str, Any], prose: dict[str, Any]) -> str:
    sections = ["About the role", prose["summary"], "", "What you will do"]
    sections.extend(f"- {item}" for item in prose["responsibilities"])
    requirements = requirements_text(fields)
    if requirements:
        sections.append("")
        sections.extend(requirements.replace("Requirements:", "What you need:").replace("Nice to have:", "\nNice to have:").splitlines())
    sections.extend(["", "How we hire",
                     "We assess every application against the listed requirements using the evidence you provide. Applications "
                     "are reviewed blind first: your name and contact details stay hidden until shortlisting. You may be "
                     "invited to a short work sample and a structured interview. We welcome applicants from all backgrounds "
                     "and will make reasonable adjustments on request."])
    return "\n".join(sections).replace("\n\n\n", "\n\n").strip()


ProseWriter = Callable[[dict[str, Any]], "dict[str, Any] | None"]


def build_preview(fields: dict[str, Any], *, inferred: list[str] | None = None,
                  writer: ProseWriter | None = None) -> tuple[dict[str, Any], dict[str, Any], bool]:
    """(create_job args, preview draft, prose written by the LLM?) for a complete set of fields."""
    prose = template_prose(fields)
    used_writer = False
    if writer is not None and not fields.get("sourceDescription"):
        written = writer(fields)
        if written:
            prose, used_writer = written, True
    description = fields.get("sourceDescription") or compose_description(fields, prose)
    # Requirements come from the requirement lines (or a pasted job description), never from the boilerplate.
    source = "\n\n".join(part for part in (fields.get("sourceDescription"), requirements_text(fields)) if part)
    level = fields.get("experienceLevel")
    title = str(fields["title"])[:200]
    compiled = compile_rules(CompileRequest(title=title, description=source[:20000], experienceLevel=level)) if source.strip() else None
    requirements = []
    for item in compiled.requirements if compiled else []:
        knockout = item.knockout and item.mandatory and item.type != "OTHER"
        requirements.append({"type": item.type, "description": item.description, "mandatory": item.mandatory,
                             "knockout": knockout, "minimumYears": item.minimumYears, "alternatives": item.alternatives,
                             "proficiency": item.proficiency, "rationale": item.rationale})
    # Wording warnings over everything a reader (or the hiring team) wrote: description and the user's own words.
    review_text = "\n".join([description, *[str(text) for text in fields.get("userText", [])]])[:20000]
    warnings = [warning.model_dump() for warning in wording_warnings(
        CompileRequest(title=title, description=review_text, experienceLevel=level), compiled.requirements if compiled else [])]
    args = {
        "title": fields["title"], "department": fields.get("department"), "location": compose_location(fields),
        "jobType": fields.get("jobType"), "experienceLevel": fields.get("experienceLevel"),
        "openings": fields.get("openings"), "deadline": fields.get("deadline"),
        "salaryMin": fields.get("salaryMin"), "salaryMax": fields.get("salaryMax"),
        "summary": prose["summary"], "responsibilities": prose["responsibilities"], "description": description,
        "requirements": [{key: value for key, value in item.items() if key != "rationale"} for item in requirements],
    }
    args = {key: value for key, value in args.items() if value is not None}
    preview = {**args, "requirements": requirements, "warnings": warnings, "inferred": inferred or [],
               "descriptionSource": "llm" if used_writer else ("user" if fields.get("sourceDescription") else "template")}
    return args, preview, used_writer


def summary_line(args: dict[str, Any], preview: dict[str, Any]) -> str:
    parts = [args["title"], args.get("department"), args.get("location"), JOB_TYPES.get(args.get("jobType", ""), None),
             LEVELS.get(args.get("experienceLevel", ""), None), _openings_phrase(args.get("openings"))]
    deadline = args.get("deadline")
    parts.append(f"closes {date.fromisoformat(deadline).strftime('%d %b %Y')}" if deadline else "no deadline")
    requirements = preview["requirements"]
    must = sum(1 for item in requirements if item["mandatory"])
    gates = sum(1 for item in requirements if item["knockout"])
    nice = len(requirements) - must
    detail = f"{len(requirements)} requirement{'s' if len(requirements) != 1 else ''} ({must} must-have"
    detail += f", {gates} gate{'s' if gates != 1 else ''}" if gates else ""
    detail += f", {nice} nice-to-have)" if nice else ")"
    warnings = len(preview["warnings"])
    if warnings:
        detail += f", {warnings} wording warning{'s' if warnings != 1 else ''}"
    return "Create job: " + " · ".join(part for part in parts if part) + " — " + detail + "."
