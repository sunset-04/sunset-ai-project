package com.sunset.ai.tools;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.sunset.ai.entity.po.Course;
import com.sunset.ai.entity.po.CourseReservation;
import com.sunset.ai.entity.po.School;
import com.sunset.ai.entity.query.CourseQuery;
import com.sunset.ai.service.ICourseReservationService;
import com.sunset.ai.service.ICourseService;
import com.sunset.ai.service.ISchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CourseTools {

    private final ICourseService courseService;
    private final ICourseReservationService courseReservationService;
    private final ISchoolService schoolService;


    @Tool(description = "根据条件查询课程")
    public List<Course> queryCourse(@ToolParam(description = "查询的条件") CourseQuery query) {

        // 判断查询条件是否为空
        if(query == null) {
            return courseService.list();
        }
        if (query.getEdu() != null && (query.getEdu() < 0 || query.getEdu() > 4)) {
            throw new IllegalArgumentException("学历条件必须为 0-4");
        }
        if (query.getSorts() != null) {
            for (CourseQuery.Sort sort : query.getSorts()) {
                if (sort == null || sort.getField() == null || !Set.of("price", "duration").contains(sort.getField())) {
                    throw new IllegalArgumentException("排序字段仅支持 price 或 duration");
                }
            }
        }
        QueryChainWrapper<Course> wrapper = courseService.query()
                .eq(query.getType() != null, "type", query.getType())
                .le(query.getEdu() != null, "edu", query.getEdu());
        if(query.getSorts() != null && !query.getSorts().isEmpty()){
            query.getSorts().forEach(sort -> {
                wrapper.orderBy(true, !Boolean.FALSE.equals(sort.getAsc()), sort.getField());
            });
        }
        return wrapper.list();
    }

    @Tool(description = "查询所有校区")
    public List<School> querySchool() {
        return schoolService.list();
    }

    @Tool(description = "生成预约单")
    public Integer queryCourseReservation(
            @ToolParam(description = "预约课程") String course,
            @ToolParam(description = "学生姓名") String studentName,
            @ToolParam(description = "联系方式") String contactInfo,
            @ToolParam(description = "预约校区") String school,
            @ToolParam(description = "备注", required = false) String remark) {
        if (course == null || course.isBlank() || course.length() > 100
                || school == null || school.isBlank() || school.length() > 100
                || studentName == null || studentName.isBlank() || studentName.length() > 50
                || contactInfo == null || !contactInfo.matches("[0-9+() -]{6,30}")
                || (remark != null && remark.length() > 500)) {
            throw new IllegalArgumentException("请检查课程、校区、姓名与联系方式，备注不能超过 500 字符");
        }
        if (courseService.query().eq("name", course).count() == 0
                || schoolService.query().eq("name", school).count() == 0) {
            throw new IllegalArgumentException("课程或校区不存在，请重新查询后预约");
        }
        CourseReservation courseReservation = new CourseReservation();
        courseReservation.setCourse(course)
                .setStudentName(studentName)
                .setContactInfo(contactInfo)
                .setSchool(school)
                .setRemark(remark);
        if (!courseReservationService.save(courseReservation) || courseReservation.getId() == null) {
            throw new IllegalStateException("预约保存失败，请稍后重试");
        }
        return courseReservation.getId();
    }
}
