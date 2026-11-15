package com.recruitpro.assessment.service;

import com.recruitpro.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upload validation of work-sample files: type by extension AND magic bytes, size, count, names; ZIPs not extracted. */
class WorkSampleFileServiceTest {

    private final WorkSampleFileService service = new WorkSampleFileService(null, null, 1);

    static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    }

    static byte[] jpg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
    }

    static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void acceptsEveryAllowedTypeWithMatchingContent() throws IOException {
        assertEquals("application/pdf", service.validate("answer.pdf", pdf()).contentType());
        assertEquals("image/png", service.validate("diagram.PNG", png()).contentType());
        assertEquals("image/jpeg", service.validate("photo.jpg", jpg()).contentType());
        assertEquals("image/jpeg", service.validate("photo.jpeg", jpg()).contentType());
        assertEquals("application/zip", service.validate("code.zip", zip("src/Main.java")).contentType());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                service.validate("report.docx", zip("[Content_Types].xml", "word/document.xml")).contentType());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                service.validate("slides.pptx", zip("[Content_Types].xml", "ppt/presentation.xml")).contentType());
        WorkSampleFileService.ValidatedFile file = service.validate("answer.pdf", pdf());
        assertEquals(64, file.sha256().length());
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() throws IOException {
        assertThrows(BadRequestException.class, () -> service.validate("answer.pdf", png()));
        assertThrows(BadRequestException.class, () -> service.validate("photo.png", jpg()));
        assertThrows(BadRequestException.class, () -> service.validate("photo.jpg", pdf()));
        byte[] plainZip = zip("src/Main.java");
        assertThrows(BadRequestException.class, () -> service.validate("report.docx", plainZip));
        assertThrows(BadRequestException.class, () -> service.validate("slides.pptx",
                zip("[Content_Types].xml", "word/document.xml")));
        assertThrows(BadRequestException.class, () -> service.validate("code.zip", pdf()));
    }

    @Test
    void rejectsOtherTypesEmptyAndOversizedFiles() {
        assertThrows(BadRequestException.class, () -> service.validate("tool.exe", pdf()));
        assertThrows(BadRequestException.class, () -> service.validate("noextension", pdf()));
        assertThrows(BadRequestException.class, () -> service.validate("empty.pdf", new byte[0]));
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy(pdf(), 0, big, 0, pdf().length);
        assertThrows(BadRequestException.class, () -> service.validate("big.pdf", big));
    }

    @Test
    void allowsAtMostThreeFilesAndIgnoresEmptyParts() {
        List<MultipartFile> four = List.of(
                new MockMultipartFile("files", "a.pdf", "application/pdf", pdf()),
                new MockMultipartFile("files", "b.pdf", "application/pdf", pdf()),
                new MockMultipartFile("files", "c.pdf", "application/pdf", pdf()),
                new MockMultipartFile("files", "d.pdf", "application/pdf", pdf()));
        assertThrows(BadRequestException.class, () -> service.validate(four));
        List<MultipartFile> withEmpty = List.of(
                new MockMultipartFile("files", "a.pdf", "application/pdf", pdf()),
                new MockMultipartFile("files", "", "application/octet-stream", new byte[0]));
        assertEquals(1, service.validate(withEmpty).size());
        assertTrue(service.validate(null).isEmpty());
    }

    @Test
    void namesLoseTheirPathAndControlCharacters() {
        assertEquals("answer.pdf", WorkSampleFileService.safeName("C:\\Users\\x\\answer.pdf"));
        assertEquals("answer.pdf", WorkSampleFileService.safeName("../../answer.pdf"));
        assertEquals("ab.pdf", WorkSampleFileService.safeName("a\"b\u0007.pdf"));
        assertThrows(BadRequestException.class, () -> WorkSampleFileService.safeName("   "));
    }

    @Test
    void zipCentralDirectoryIsReadWithoutExtracting() throws IOException {
        Set<String> names = WorkSampleFileService.zipEntryNames(zip("a.txt", "dir/b.txt"), 100);
        assertEquals(Set.of("a.txt", "dir/b.txt"), names);
        assertTrue(WorkSampleFileService.zipEntryNames(pdf(), 100).isEmpty());
    }
}
