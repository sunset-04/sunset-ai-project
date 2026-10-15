package com.sunset.ai.tools;

import com.sunset.ai.entity.query.CourseQuery;
import com.sunset.ai.service.ICourseService;
import com.sunset.ai.service.ICourseReservationService;
import com.sunset.ai.service.ISchoolService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseToolsTest {
    private final ICourseService courses = mock(ICourseService.class);
    private final ICourseReservationService reservations = mock(ICourseReservationService.class);
    private final ISchoolService schools = mock(ISchoolService.class);
    private final CourseTools tools = new CourseTools(courses, reservations, schools);

    @Test void modelGeneratedSqlFieldsMustBelongToTheWhitelist() {
        CourseQuery query = new CourseQuery();
        CourseQuery.Sort sort = new CourseQuery.Sort();
        sort.setField("price DESC; DROP TABLE course");
        query.setSorts(List.of(sort));
        assertThatThrownBy(() -> tools.queryCourse(query)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(courses);
    }

    @Test void invalidReservationCannotBeWrittenToTheDatabase() {
        assertThatThrownBy(() -> tools.queryCourseReservation("Java", "", "not-a-phone", "Shenzhen", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(courses, reservations, schools);
    }

    @Test void missingSortFieldIsAValidationErrorRatherThanANullPointerFailure() {
        CourseQuery query = new CourseQuery();
        query.setSorts(List.of(new CourseQuery.Sort()));
        assertThatThrownBy(() -> tools.queryCourse(query)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(courses);
    }
}
