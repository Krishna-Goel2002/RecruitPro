"""Occupation lexicon: what a job title says a person practised (matching, finding W2).

EXPERIENCE requirements name a practice ("3+ years of backend development", "B2B sales experience",
"clinical nursing experience"), while resumes name a job title ("SDE-2", "Key Account Manager",
"Staff Nurse"). Lexical overlap between the two is rare, so the matcher asks this lexicon which
practices a title implies.

* A title is normalised first: seniority and grade words ("Senior", "Sr.", "Lead", "II", "SDE-2",
  "Trainee", "Intern") are dropped, so "Sr. Software Engineer II" reads as "software engineer".
* Each occupation is a title pattern plus the taxonomy skills it practises unconditionally, and
  optional *conditional* practices that need corroboration from the role's own block: a "Software
  Engineer" is a backend developer only when the role mentions backend technology (Java, Spring,
  SQL, Kafka, REST APIs ...), and a "Sales Executive" only practises B2B sales when the block shows
  B2B markers (account management, SaaS or channel sales, a CRM ...).
* Occupations also carry *topic words* (stemmed), for requirements that name no taxonomy skill
  ("experience in a manufacturing plant" against "Production Engineer").

The output only ever widens EXPERIENCE relevance (and the timeline depth used by ADVANCED/EXPERT
proficiency); it never creates SKILL evidence, so a title alone cannot satisfy a skill requirement.
Unknown skill ids are ignored, so the lexicon cannot break when the taxonomy changes.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache

from app.pipeline.text import stem
from app.taxonomy.taxonomy import get_taxonomy

_SENIORITY_RE = re.compile(
    r"\b(?:senior|sr|junior|jr|lead|staff|chief|head|deputy|assistant|asst|associate|assoc|trainee|intern|apprentice|"
    r"graduate|grad|probationary|executive(?=\s+(?:director|officer)))\b\.?|\b(?:i{1,3}|iv|v)\b|\b[1-5]\b|"
    r"(?<=[a-z])-?\s?(?:i{1,3}|[1-5])\b|\bgrade\s+\w+\b|\blevel\s+\w+\b",
    re.I,
)

# Technology that marks a role as backend, frontend or mobile development.
_BACKEND = frozenset({
    "java", "spring", "spring-boot", "spring-mvc", "spring-cloud", "hibernate", "jpa", "java-ee", "node-js", "express",
    "nestjs", "django", "flask", "fastapi", "dotnet", "asp-net", "c-sharp", "go", "ruby-on-rails", "laravel", "php",
    "rest-api", "graphql", "grpc", "microservices", "kafka", "rabbitmq", "sql", "mysql", "postgresql", "mongodb", "redis",
    "oracle", "microsoft-sql-server", "elasticsearch", "api-design", "distributed-systems", "event-driven-architecture",
    "backend-development", "scala", "kotlin",
})
_FRONTEND = frozenset({"react", "angular", "angularjs", "vue-js", "next-js", "svelte", "javascript", "typescript", "html",
                       "css", "redux", "tailwind-css", "frontend-development", "responsive-design", "web-accessibility"})
_MOBILE = frozenset({"android", "ios", "flutter", "react-native", "swift", "swiftui", "kotlin", "jetpack-compose", "dart",
                     "xamarin", "mobile-development"})
# B2B evidence in a sales role's block (inside sales and business development alone are not B2B evidence).
_B2B = frozenset({"b2b-sales", "saas-sales", "enterprise-sales", "account-management", "channel-sales", "solution-selling",
                  "consultative-selling", "salesforce", "hubspot", "zoho-crm", "linkedin-sales-navigator",
                  "sales-pipeline-management", "territory-management", "client-relationship-management", "proposal-writing",
                  "contract-negotiation"})
# Data work in an analyst's block.
# (Spreadsheets are left out: every business role uses Excel.)
_DATA_TOOLS = frozenset({"sql", "mysql", "postgresql", "power-bi", "tableau", "looker", "python", "pandas", "r",
                         "statistics", "data-visualization", "dax", "data-analysis", "business-intelligence", "data-cleaning",
                         "spss", "sas", "google-bigquery", "snowflake"})
# UX practice in a UI or visual designer's block.
_UX = frozenset({"user-research", "usability-testing", "wireframing", "prototyping", "interaction-design",
                 "information-architecture", "ux-design", "customer-journey-mapping", "design-thinking"})
# Corporate-law practice in a lawyer's block.
_CORPORATE_LAW = frozenset({"corporate-law", "companies-act", "contract-drafting", "contract-review", "sebi-regulations",
                            "mergers-and-acquisitions", "due-diligence", "company-secretarial", "fema", "legal-advisory"})
_LEGAL = frozenset({"legal-research", "legal-drafting", "litigation", "civil-litigation", "pleadings", "contract-drafting",
                    "contract-review", "corporate-law", "legal-documentation", "arbitration", "intellectual-property"})
_SCHOOL = frozenset({"lesson-planning", "classroom-management", "cbse-curriculum", "icse-curriculum", "ib-curriculum",
                     "cambridge-curriculum", "curriculum-development", "teaching", "student-engagement", "parent-communication",
                     "educational-assessment", "assessment-design", "differentiated-instruction", "online-teaching"})


@dataclass(frozen=True)
class Occupation:
    name: str
    pattern: re.Pattern[str]
    skills: frozenset[str]
    conditional: tuple[tuple[frozenset[str], frozenset[str]], ...] = ()  # (markers in the block, implied skills)
    topics: frozenset[str] = frozenset()
    corroborate: bool = False  # the title alone implies nothing ("Software Engineer", "Coordinator")


def _occupation(name: str, pattern: str, skills: tuple[str, ...] = (), conditional: tuple = (), topics: str = "",
                corroborate: bool = False) -> Occupation:
    return Occupation(name, re.compile(pattern, re.I), frozenset(skills),
                      tuple((frozenset(markers), frozenset(implied)) for markers, implied in conditional),
                      frozenset(stem(word) for word in topics.split()), corroborate)


_OCCUPATIONS: tuple[Occupation, ...] = (
    # Software
    _occupation("backend developer", r"\b(?:back[\s-]?end|server[\s-]?side|api|java|j2ee|python|node(?:\.?js)?|golang|go|\.net|"
                r"dotnet|c#|php|ruby|django|spring)\s+(?:developer|engineer|programmer)\b",
                ("backend-development",), topics="backend server"),
    _occupation("frontend developer", r"\b(?:front[\s-]?end|ui|react|angular|vue|javascript|web)\s+(?:developer|engineer)\b",
                ("frontend-development",), topics="frontend web"),
    _occupation("full-stack developer", r"\bfull[\s-]?stack\b", ("full-stack-development", "backend-development",
                                                                  "frontend-development"), topics="fullstack web"),
    _occupation("mobile developer", r"\b(?:mobile|android|ios|flutter|react\s+native)\s+(?:app\s+)?(?:developer|engineer)\b",
                ("mobile-development",), topics="mobile app"),
    _occupation("software engineer", r"\b(?:software|application|applications|product|platform)\s+(?:developer|engineer|"
                r"engineering|development|programmer)\b|\bsde\b|\bswe\b|\bprogrammer\b|\bsoftware\s+development\s+engineer\b|\bmember\s+of\s+technical\s+staff\b|"
                r"\bdeveloper\b|\bcoder\b",
                (), ((_BACKEND, ("backend-development",)), (_FRONTEND, ("frontend-development",)),
                     (_MOBILE, ("mobile-development",))), topics="software development", corroborate=True),
    _occupation("qa engineer", r"\b(?:qa|sdet|software\s+test(?:ing)?|test(?:ing)?|automation\s+test(?:ing)?|manual\s+test(?:ing)?)"
                r"\s+(?:engineer|analyst|lead|specialist|executive|manager)\b|\bsdet\b|\b(?:software\s+)?tester\b",
                ("software-testing",),
                ((frozenset({"selenium", "cypress", "playwright", "appium", "test-automation", "testng", "pytest"}),
                  ("test-automation",)),), topics="testing quality"),
    _occupation("devops engineer", r"\b(?:devops|dev\s*ops|site\s+reliability|sre|platform\s+engineer|cloud\s+engineer|"
                r"infrastructure\s+engineer|build\s+(?:and|&)\s+release)\b", ("devops",),
                ((frozenset({"kubernetes", "prometheus", "grafana", "incident-response"}), ("site-reliability-engineering",)),),
                topics="infrastructure cloud"),
    _occupation("data engineer", r"\b(?:data|etl|big\s+data)\s+engineer\b|\betl\s+developer\b", ("data-engineering",),
                topics="data pipeline"),
    # Data
    # Business, product and financial analysts are other practices (requirements, product, finance).
    _occupation("data analyst", r"\b(?:data|bi|mis|reporting|insights?|analytics|marketing|quantitative)\s+analyst\b|\banalytics\s+(?:manager|lead|consultant|specialist|head|"
                r"engineer)\b|\bhead\s+of\s+analytics\b|\bstatistician\b",
                ("data-analysis",), ((frozenset({"power-bi", "tableau", "looker", "qlik", "business-intelligence", "dax"}),
                                      ("business-intelligence",)),), topics="analytics data analysis"),
    # A bare "Analyst" ("Analyst Trainee", "Senior Analyst") is a data analyst when the block uses data tools.
    # A business analyst does requirements work in a product team but data analysis in an analytics team: the
    # block's data tools decide.
    _occupation("analyst", r"^(?:business\s+)?analyst$|\bbusiness\s+analytics\b", (), ((_DATA_TOOLS, ("data-analysis",)),),
                topics="analytics data", corroborate=True),
    _occupation("bi developer", r"\b(?:bi|business\s+intelligence|power\s+bi|tableau)\s+(?:developer|engineer|analyst|"
                r"consultant)\b", ("business-intelligence", "data-analysis"), topics="analytics reporting"),
    _occupation("data scientist", r"\b(?:data|decision|research)\s+scientist\b|\bmachine\s+learning\s+engineer\b|"
                r"\bml\s+engineer\b|\bai\s+engineer\b", ("data-science", "data-analysis"), topics="analytics data"),
    # Product and design
    _occupation("product manager", r"\bproduct\s+(?:manager|owner|lead|head|director)\b|\bhead\s+of\s+product\b|\bapm\b|"
                r"\b(?:a|s|e)?vp\s*[-–—,]?\s*(?:of\s+)?product\b|\bdirector\s+of\s+product\b|\bchief\s+product\s+officer\b|"
                r"\bproduct\s+management\b", ("product-management",),
                topics="product"),
    _occupation("ux designer", r"\b(?:ux|ui\s*/\s*ux|ui\s+ux|user\s+experience|interaction|product|experience|service)\s+"
                r"designer\b|\bux\s+(?:researcher|lead|architect)\b|\bdesign\s+lead\b",
                ("ux-design",), ((frozenset({"user-research", "usability-testing"}), ("user-research",)),),
                topics="design user experience"),
    _occupation("ui designer", r"\b(?:ui|visual|web)\s+designer\b", ("ui-design",),
                ((_UX, ("ux-design",)),), topics="design"),
    _occupation("interaction designer", r"\binteraction\s+designer\b", ("interaction-design", "ux-design"), topics="design"),
    # Sales
    _occupation("b2b sales", r"\b(?:account\s+(?:executive|manager|director)|key\s+account|enterprise\s+(?:sales|account)|"
                r"corporate\s+sales|channel\s+(?:sales|partner|manager)|territory\s+(?:sales|manager)|solutions?\s+"
                r"(?:consultant|sales)|pre[\s-]?sales|bdr|sdr|sales\s+development\s+representative)\b",
                ("b2b-sales",), topics="sales"),
    # Business development and inside sales are B2B in SaaS and services, but B2C in much of retail and edtech:
    # the role block must show B2B work (accounts, SaaS/channel/solution selling, a B2B CRM).
    _occupation("business-to-business development", r"\bbusiness\s+development\b|\bbde\b|\bbdm\b|\binside\s+sales\b",
                (), ((_B2B, ("b2b-sales",)),), topics="sales", corroborate=True),
    _occupation("inside sales", r"\binside\s+sales\b", ("inside-sales",), topics="sales"),
    _occupation("key account manager", r"\bkey\s+account|\baccount\s+manager\b", ("account-management",), topics="sales"),
    _occupation("business development", r"\bbusiness\s+development\b|\bbde\b|\bbdm\b", ("business-development",),
                topics="sales"),
    _occupation("salesperson", r"\bsales\s*(?:person|man|woman|executive|officer|representative|rep|associate|manager|"
                r"lead|head|consultant|specialist|engineer|coordinator|intern|trainee)\b|\b(?:area|regional|zonal|branch)\s+sales\b|"
                r"\bfield\s+sales\b|\bhead\s+of\s+sales\b|\b(?:a|s|e)?vp\s*[-–—,]?\s*sales\b|\bdirector\s+of\s+sales\b|"
                r"\bsales\s+director\b", (), ((_B2B, ("b2b-sales",)),), topics="sales", corroborate=True),
    _occupation("relationship manager", r"\brelationship\s+manager\b|\bclient\s+(?:servicing|relationship)\b", (),
                ((_B2B, ("b2b-sales", "account-management")),), topics="sales client", corroborate=True),
    # Healthcare and education
    _occupation("nurse", r"\bnurs(?:e|es|ing)\b|\bmidwife\b|\bgnm\b|\banm\b|\bmatron\b|"
                r"\bward\s+(?:sister|in-?\s?charge|manager|supervisor)\b",
                ("nursing-care", "patient-care"), ((frozenset({"critical-care", "icu", "ventilator-management"}),
                                                    ("critical-care",)),), topics="clinical nursing patient hospital"),
    _occupation("teacher", r"\bteachers?\b|\bpgt\b|\btgt\b|\bprt\b|\blecturer\b|\bprofessor\b|\bfaculty\b|\btutor\b|\beducator\b|"
                r"\binstructor\b|\bheadm(?:aster|istress)\b|\b(?:vice\s+)?principal\s*$|\bvice\s+principal\b",
                ("teaching",), topics="teaching school classroom"),
    _occupation("school leader", r"\bacademic\s+(?:coordinator|head|director)\b|\bhead\s+of\s+department\b|\bhod\b|"
                r"\bcoordinator\b|\bprincipal\b", (), ((_SCHOOL, ("teaching",)),), topics="school", corroborate=True),
    # Operations and manufacturing
    _occupation("supply chain", r"\b(?:supply\s+chain|scm|logistics|procurement|purchase|purchasing|sourcing|inventory|"
                r"warehouse|stores?|materials?|demand|distribution|fulfil(?:l)?ment|dispatch)\s*"
                r"(?:manager|executive|analyst|lead|head|officer|coordinator|planner|specialist|associate|engineer|"
                r"supervisor|incharge|in-charge|keeper|intern|trainee)\b|\bbuyer\b|\bstorekeeper\b|\bdemand\s+planner\b",
                ("supply-chain-management", "operations-management"),
                ((frozenset({"procurement", "vendor-management", "strategic-sourcing"}), ("procurement",)),
                 (frozenset({"inventory-management", "warehouse-management"}), ("inventory-management",))),
                topics="supply chain operations logistics"),
    _occupation("operations", r"\b(?:operations|ops)\s+(?:manager|executive|analyst|lead|head|officer|coordinator|"
                r"associate|supervisor|specialist|planner|intern|trainee)\b|\bhead\s+of\s+operations\b",
                ("operations-management",),
                ((frozenset({"supply-chain-management", "logistics", "procurement", "inventory-management",
                             "warehouse-management", "demand-planning", "sap-mm"}), ("supply-chain-management",)),),
                topics="operations"),
    _occupation("manufacturing engineer", r"\b(?:production|manufacturing|plant|process|quality|maintenance|shift|tool|"
                r"industrial|mechanical|assembly|shop\s+floor)\s+(?:engineer|supervisor|manager|incharge|in-charge|head|"
                r"executive|technician)\b|\bplant\s+head\b",
                (), ((frozenset({"production-planning", "lean-manufacturing", "six-sigma", "quality-control", "kaizen", "5s"}),
                      ("production-planning",)),), topics="manufacturing plant production shop floor factory"),
    _occupation("civil engineer", r"\b(?:civil|site|structural|construction|project|highway|planning|billing)\s+engineer\b|"
                r"\bquantity\s+surveyor\b|\bsite\s+(?:supervisor|incharge|in-charge)\b", (),
                topics="civil construction site structural"),
    # Business functions
    _occupation("recruiter", r"\brecruit(?:er|ment|ing)\b|\btalent\s+acquisition\b|\bstaffing\s+(?:executive|specialist)\b|"
                r"\bsourcer\b", ("recruitment", "talent-acquisition"), topics="hiring recruitment"),
    _occupation("hr generalist", r"\b(?:hr|human\s+resources?|people)\s+(?:executive|generalist|manager|business\s+partner|"
                r"officer|associate|coordinator|specialist|operations|lead|head)\b|\bhrbp\b", ("hr-operations",),
                ((frozenset({"recruitment", "talent-acquisition", "sourcing"}), ("recruitment",)),), topics="human resources hr"),
    _occupation("accountant", r"\baccountants?\b|\baccounts\s+(?:executive|assistant|officer|manager|associate|head)\b|"
                r"\bchartered\s+accountant\b|\bfinance\s+(?:executive|manager|associate|officer|controller)\b|\bcontroller\b|"
                r"\bbook\s*keeper\b|\barticle\s+(?:assistant|clerk|trainee)\b", ("accounting",),
                ((frozenset({"gst", "tds", "income-tax", "taxation", "indirect-tax"}), ("taxation",)),),
                topics="accounting finance accounts"),
    # Internal and statutory audit are different practices; the block says which one ("Audit Associate" at a CA firm
    # usually means statutory audit).
    _occupation("auditor", r"\baudit(?:or|ors)?\b(?:\s+(?:associate|assistant|senior|manager|executive|analyst))?",
                (), ((frozenset({"internal-audit", "internal-controls", "sox-compliance"}), ("internal-audit",)),
                     (frozenset({"statutory-audit", "tax-audit", "external-audit"}), ("statutory-audit",))),
                topics="audit", corroborate=True),
    _occupation("tax consultant", r"\btax(?:ation)?\s+(?:consultant|associate|manager|analyst|executive|specialist)\b",
                ("taxation",), topics="tax"),
    _occupation("customer support", r"\b(?:customer|client|technical|tech)\s+(?:support|service|care|success|experience)\b|"
                r"\bsupport\s+(?:executive|associate|engineer|specialist|agent|representative)\b|\bhelp\s*desk\b|"
                r"\bcall\s+cent(?:er|re)\b|\bcsr\b|\bcontact\s+cent(?:er|re)\b",
                ("customer-service",), topics="customer support service"),
    _occupation("digital marketer", r"\b(?:digital|performance|growth|seo|sem|social\s+media|content|email|online)\s+"
                r"(?:marketing\s+)?(?:executive|manager|specialist|analyst|strategist|marketer|lead|associate)\b",
                ("digital-marketing",), topics="marketing"),
    _occupation("marketer", r"\bmarketing\s+(?:executive|manager|specialist|associate|lead|head|coordinator|analyst)\b|"
                r"\bbrand\s+manager\b|\bmarketer\b", ("marketing-strategy",), topics="marketing brand"),
    _occupation("legal associate", r"\blegal\s+(?:associate|counsel|executive|officer|manager|advisor|adviser|analyst|"
                r"assistant)\b|\badvocate\b|\blawyer\b|\battorney\b|\bsolicitor\b|\bparalegal\b|\blaw\s+(?:clerk|associate)\b|"
                r"\bcounsel\b", ("legal-research", "legal-drafting"),
                ((frozenset({"litigation", "civil-litigation", "pleadings"}), ("litigation",)),
                 (_CORPORATE_LAW, ("corporate-law",))), topics="legal law"),
    # "Senior Associate" / "Principal Associate" at a law firm: a lawyer when the block shows legal work.
    _occupation("law firm associate", r"^\s*(?:senior|junior|principal|managing)?\s*associate\s*$", (),
                ((_LEGAL, ("legal-research", "legal-drafting")), (_CORPORATE_LAW, ("corporate-law",))),
                topics="legal law", corroborate=True),
    _occupation("company secretary", r"\bcompany\s+secretary\b|\bcompliance\s+officer\b", ("company-secretarial",),
                topics="legal compliance secretarial"),
    _occupation("project manager", r"\bproject\s+(?:manager|lead|coordinator)\b|\bprogram(?:me)?\s+manager\b|"
                r"\bscrum\s+master\b|\bdelivery\s+manager\b", ("project-management",), topics="project"),
)


def normalise_title(title: str) -> str:
    """Lower-case title without seniority/grade words: "Sr. Software Engineer II" -> "software engineer"."""
    text = _SENIORITY_RE.sub(" ", title.lower().replace("–", "-"))
    return " ".join(re.sub(r"[^a-z0-9+#./& \-]", " ", text).split())


@dataclass(frozen=True)
class Practice:
    skills: frozenset[str]
    topics: frozenset[str]
    occupations: tuple[str, ...]


@lru_cache(maxsize=4096)
def _matches(title: str) -> tuple[Occupation, ...]:
    raw = title.lower()
    normal = normalise_title(title)
    return tuple(occupation for occupation in _OCCUPATIONS
                 if occupation.pattern.search(normal) or occupation.pattern.search(raw))


def practice(title: object, block_skills: frozenset[str] = frozenset()) -> Practice:
    """The practices a job title implies, corroborated by the skills named in the role's own block."""
    if not isinstance(title, str) or not title.strip():
        return Practice(frozenset(), frozenset(), ())
    known = {skill.id for skill in get_taxonomy().skills}
    skills: set[str] = set()
    topics: set[str] = set()
    names: list[str] = []
    for occupation in _matches(title.strip()[:200]):
        implied = set(occupation.skills)
        for markers, extra in occupation.conditional:
            if markers & block_skills:
                implied |= extra
        if not implied and (occupation.corroborate or not occupation.topics):
            continue  # a title that needs corroboration ("Coordinator", "Software Engineer") and got none
        skills |= implied
        topics |= occupation.topics
        names.append(occupation.name)
    return Practice(frozenset(skill for skill in skills if skill in known), frozenset(topics), tuple(names))
