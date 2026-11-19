package com.studyroom;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 설계 규칙을 소스의 import로 검사한다.
 *  1. 호출은 위에서 아래로만: Control은 Boundary·Device를, Device는 Boundary를 import하지 않는다
 *  2. 다른 구역은 인터페이스로만: Control 구역의 구현은 다른 구역의 구현 패키지를 import하지 않는다
 *  3. 인터페이스(api) 패키지는 다른 것에 기대지 않는다: domain과 자기 api만 본다
 *  4. Boundary는 Control의 api만 본다 (Device를 직접 보지 않는다)
 *  5. 기억하는 데이터는 Device에만: Control 구현은 업무 데이터를 컬렉션 필드로 들고 있지 않는다
 */
public class ArchitectureRulesTest {
    private static final String BASE = "com.studyroom.";
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?(com\\.studyroom\\.[\\w.]+?)\\.[A-Z]\\w*(?:\\.\\w+|\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern WILDCARD = Pattern.compile("^import (com\\.studyroom\\.[\\w.]+)\\.\\*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);

    private record Dep(String file, String fromPkg, String toPkg) {
    }

    private static List<Dep> dependencies() throws IOException {
        Path root = Stream.of("src/main/java", "core/src/main/java").map(Path::of)
                .filter(Files::isDirectory).findFirst().orElseThrow();
        List<Dep> deps = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher pm = PACKAGE.matcher(src);
                if (!pm.find()) continue;
                String from = pm.group(1);
                for (Pattern p : List.of(IMPORT, WILDCARD)) {
                    Matcher m = p.matcher(src);
                    while (m.find()) deps.add(new Dep(f.getFileName().toString(), from, m.group(1)));
                }
            }
        }
        return deps;
    }

    private static boolean in(String pkg, String layer) {
        return pkg.equals(BASE + layer) || pkg.startsWith(BASE + layer + ".");
    }

    private static String zoneOf(String pkg) {
        String rest = pkg.substring((BASE + "control.").length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    @Test
    public void layersOnlyCallDownward() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Dep d : dependencies()) {
            if (in(d.fromPkg(), "control") && (in(d.toPkg(), "boundary") || in(d.toPkg(), "device"))) violations.add(d.toString());
            if (in(d.fromPkg(), "device") && in(d.toPkg(), "boundary")) violations.add(d.toString());
            if (in(d.fromPkg(), "domain") && !in(d.toPkg(), "domain")) violations.add(d.toString());
        }
        assertTrue(violations.toString(), violations.isEmpty());
    }

    @Test
    public void zonesSeeEachOtherOnlyThroughInterfaces() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Dep d : dependencies()) {
            if (!in(d.fromPkg(), "control") || !in(d.toPkg(), "control")) continue;
            boolean otherZone = !zoneOf(d.fromPkg()).equals(zoneOf(d.toPkg()));
            if (otherZone && !d.toPkg().endsWith(".api")) violations.add(d.toString());
        }
        assertTrue(violations.toString(), violations.isEmpty());
    }

    @Test
    public void interfacePackagesDependOnlyOnDomainAndThemselves() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Dep d : dependencies()) {
            if (!d.fromPkg().endsWith(".api")) continue;
            if (!in(d.toPkg(), "domain") && !d.toPkg().equals(d.fromPkg())) violations.add(d.toString());
        }
        assertTrue(violations.toString(), violations.isEmpty());
    }

    @Test
    public void boundaryAndDeviceSeeControlOnlyThroughInterfaces() throws IOException {
        List<String> violations = new ArrayList<>();
        boolean sawAny = false;
        for (Dep d : dependencies()) {
            sawAny = true;
            boolean outer = in(d.fromPkg(), "boundary") || in(d.fromPkg(), "device");
            if (outer && in(d.toPkg(), "control") && !d.toPkg().endsWith(".api")) violations.add(d.toString());
            if (in(d.fromPkg(), "boundary") && in(d.toPkg(), "device")) violations.add(d.toString());
        }
        assertTrue("소스를 읽지 못했다", sawAny);
        assertFalse(violations.toString(), !violations.isEmpty());
    }

    /** 업무 데이터가 아닌 것만 예외로 둔다. 새 예외를 넣으려면 이유를 같이 적는다. */
    private static final java.util.Set<String> ALLOWED_CONTROL_FIELDS = java.util.Set.of(
            "PolicyRegistry.policies",       // 시작할 때 등록하는 정책 목록(설정값)
            "AllocationProcessor.threads");  // 워커 스레드 핸들

    private static final Pattern COLLECTION_FIELD = Pattern.compile(
            "^\\s*private (?:final )?(?:static )?[\\w<>, ?]*(?:Map|Set|List|Queue|Deque)<[^;{(]*?>\\s+(\\w+)\\s*=\\s*new",
            Pattern.MULTILINE);

    @Test
    public void controlKeepsNoDataInCollectionFields() throws IOException {
        Path root = Stream.of("src/main/java/com/studyroom/control", "core/src/main/java/com/studyroom/control").map(Path::of)
                .filter(Files::isDirectory).findFirst().orElseThrow();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/api/")).toList()) {
                String cls = f.getFileName().toString().replace(".java", "");
                Matcher m = COLLECTION_FIELD.matcher(Files.readString(f));
                while (m.find()) {
                    String field = cls + "." + m.group(1);
                    if (!ALLOWED_CONTROL_FIELDS.contains(field)) violations.add(field);
                }
            }
        }
        assertTrue("Control이 데이터를 직접 들고 있다(Device 저장소로 옮길 것): " + violations, violations.isEmpty());
    }
}
