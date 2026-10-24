package mx.artemisa.artemisaapi.student.dto;

import java.util.List;

public record StudentPageResponse(
        List<StudentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}