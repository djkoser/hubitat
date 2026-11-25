package support

/**
 * The hub's class allowlist (Hubitat developer docs, "Allowed Imports"), enforced on imports
 * and on fully-qualified java.io / java.nio / java.net references. hubitat_ci 0.17 restricts
 * threads, exec, reflection and custom classes, but lets file and socket classes through.
 */
class ImportPolicy {
    static final Set<String> CLASSES = ([
        // Concurrent
        'java.util.concurrent.ConcurrentHashMap', 'java.util.concurrent.CopyOnWriteArrayList',
        'java.util.concurrent.ConcurrentLinkedQueue', 'java.util.concurrent.SynchronousQueue',
        'java.util.concurrent.Semaphore', 'java.util.concurrent.TimeUnit',
        'java.util.concurrent.atomic.AtomicInteger', 'java.util.concurrent.atomic.AtomicIntegerArray',
        // Groovy
        'groovy.json.JsonSlurper', 'groovy.json.JsonOutput', 'groovy.json.JsonBuilder', 'groovy.util.XmlSlurper',
        'groovy.util.XmlParser', 'groovy.xml.XmlUtil', 'groovy.xml.MarkupBuilder', 'groovy.util.Node',
        'groovy.util.NodeList', 'groovy.lang.Closure', 'groovy.lang.GString', 'groovy.time.TimeCategory',
        'groovy.transform.Field', 'groovy.transform.CompileStatic',
        // Date/time
        'java.util.Date', 'java.util.Calendar', 'java.util.GregorianCalendar', 'java.util.TimeZone',
        'java.text.SimpleDateFormat', 'org.apache.commons.lang3.time.DateUtils',
        // Math
        'java.math.BigDecimal', 'java.math.BigInteger', 'java.math.RoundingMode', 'java.text.DecimalFormat',
        'java.util.Random',
        // Text/encoding
        'java.net.URLEncoder', 'java.net.URLDecoder', 'java.util.UUID', 'org.apache.commons.codec.binary.Base64',
        'org.apache.commons.lang3.StringUtils', 'org.apache.commons.lang3.StringEscapeUtils',
        'org.apache.commons.text.StringEscapeUtils', 'org.apache.commons.lang3.math.NumberUtils',
        'org.codehaus.groovy.runtime.EncodingGroovyMethods',
        // Crypto
        'javax.crypto.Mac', 'javax.crypto.Cipher', 'javax.crypto.spec.SecretKeySpec', 'javax.crypto.spec.IvParameterSpec',
        'java.security.MessageDigest', 'java.security.Signature', 'java.security.PrivateKey', 'java.security.KeyFactory',
        'java.security.spec.PKCS8EncodedKeySpec', 'java.security.InvalidKeyException',
        // XML/JSON
        'org.json.JSONObject', 'org.json.JSONArray', 'org.json.JSONException',
        // Compression
        'java.util.zip.GZIPInputStream', 'java.util.zip.GZIPOutputStream', 'java.util.zip.DeflaterInputStream',
        'java.util.zip.DeflaterOutputStream', 'java.util.zip.InflaterInputStream', 'java.util.zip.InflaterOutputStream',
        'java.util.zip.ZipInputStream', 'java.util.zip.ZipOutputStream',
        // Network
        'java.net.URI', 'javax.jmdns.JmDNS', 'javax.jmdns.ServiceEvent', 'org.quartz.CronExpression',
        // I/O
        'java.io.ByteArrayInputStream', 'java.io.ByteArrayOutputStream', 'java.io.StringReader',
        'java.io.StringWriter', 'java.io.OutputStream',
        // Collections usually auto-imported but sometimes written out
        'java.util.ArrayList', 'java.util.HashMap', 'java.util.LinkedHashMap', 'java.util.LinkedList',
        'java.util.HashSet', 'java.util.LinkedHashSet', 'java.util.TreeMap', 'java.util.TreeSet',
        'java.util.BitSet', 'java.util.Collections', 'java.util.List', 'java.util.Map', 'java.util.Set',
        'java.util.Collection',
    ] as Set)
    static final List<String> PACKAGES = ['java.time.', 'com.nimbusds.jose.', 'com.nimbusds.jwt.',
                                          'javax.xml.xpath.', 'com.hubitat.']

    static boolean allowed(String cls) {
        CLASSES.contains(cls) || PACKAGES.any { cls.startsWith(it) }
    }

    /** Disallowed classes used by a script: its imports, plus fully-qualified I/O and network references */
    static List<String> violations(String text) {
        def code = text.replaceAll(/(?s)\/\*.*?\*\//, '').replaceAll(/(?m)\/\/.*$/, '')
        def imports = (code =~ /(?m)^\s*import\s+(?:static\s+)?([\w.]+)(?:\.\*)?\s*;?\s*$/).collect { it[1] }
        def fq = (code =~ /\b(java\.(?:io|nio|net)\.[\w.]*[A-Z]\w*)/).collect { it[1] }
        (imports + fq).unique().findAll { !allowed(it) }
    }
}
