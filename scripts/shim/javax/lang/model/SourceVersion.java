package javax.lang.model;

/**
 * Android shim for {@code javax.lang.model.SourceVersion}.
 *
 * GraphHopper validates encoded-value names with this JDK class
 * (IntEncodedValueImpl.isValidEncodedValue), but it is part of the Java
 * compiler / annotation-processing API and is NOT present on the Android
 * runtime — so the first route() crashed with NoClassDefFoundError.
 *
 * ART has no javax.lang.model on its bootclasspath, so this app-provided class
 * resolves the reference. We only need the identifier-validation statics
 * GraphHopper actually calls; the implementations are faithful (real Java
 * identifier rules), so validation behaves exactly as on the JVM.
 *
 * NOTE: deliberately NOT an enum (the real class is) — GraphHopper only invokes
 * these static methods, which is all ART needs to link.
 */
public final class SourceVersion {

    private SourceVersion() {
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        for (String segment : name.toString().split("\\.", -1)) {
            if (!isIdentifier(segment) || isKeyword(segment)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        String s = name.toString();
        if (!Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        if (s == null) {
            return false;
        }
        switch (s.toString()) {
            case "abstract": case "assert": case "boolean": case "break": case "byte":
            case "case": case "catch": case "char": case "class": case "const":
            case "continue": case "default": case "do": case "double": case "else":
            case "enum": case "extends": case "final": case "finally": case "float":
            case "for": case "goto": case "if": case "implements": case "import":
            case "instanceof": case "int": case "interface": case "long": case "native":
            case "new": case "package": case "private": case "protected": case "public":
            case "return": case "short": case "static": case "strictfp": case "super":
            case "switch": case "synchronized": case "this": case "throw": case "throws":
            case "transient": case "try": case "void": case "volatile": case "while":
            case "true": case "false": case "null": case "_":
                return true;
            default:
                return false;
        }
    }
}
