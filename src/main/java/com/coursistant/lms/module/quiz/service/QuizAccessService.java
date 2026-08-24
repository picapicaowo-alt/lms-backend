package com.coursistant.lms.module.quiz.service;

import com.coursistant.lms.module.course.course.entity.Course;
import com.coursistant.lms.module.course.course.repository.CourseMapper;
import com.coursistant.lms.module.course.enrollment.entity.Enrollment;
import com.coursistant.lms.module.course.enrollment.service.CoursePermissionService;
import com.coursistant.lms.module.quiz.entity.Quiz;
import com.coursistant.lms.module.quiz.repository.QuizAttemptMapper;
import com.coursistant.lms.module.quiz.repository.QuizMapper;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class QuizAccessService {

    public static final int ARCHIVE_GRADING_DAYS = 30;

    @Resource
    private CourseMapper courseMapper;
    @Resource
    private QuizMapper quizMapper;
    @Resource
    private QuizAttemptMapper quizAttemptMapper;
    @Resource
    private CoursePermissionService coursePermissionService;
    @Resource
    private QuizTimeSupport quizTimeSupport;

    public Course requireCourse(Integer courseId) {
        if (courseId == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Course id is required");
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ApiException(ErrorType.COURSE_NOT_FOUND);
        }
        return course;
    }

    public void requireNotArchived(Course course) {
        if (QuizConstants.COURSE_ARCHIVED.equals(course.getState())) {
            throw new ApiException(ErrorType.COURSE_ARCHIVED);
        }
    }

    public Course requireCourseWritable(Integer courseId, Integer userId) {
        Course course = requireCourse(courseId);
        coursePermissionService.requireInstructor(courseId, userId);
        requireNotArchived(course);
        return course;
    }

    public Enrollment requireActiveMember(Integer courseId, Integer userId) {
        return coursePermissionService.requireActiveEnrollment(courseId, userId);
    }

    public boolean isStaffViewer(HttpServletRequest request, Integer courseId, Integer userId) {
        if (coursePermissionService.isSystemAdmin(request)) {
            return true;
        }
        Enrollment enrollment = coursePermissionService.requireActiveEnrollment(courseId, userId);
        String role = enrollment.getCourseRole();
        return CoursePermissionService.ROLE_INSTRUCTOR.equals(role)
                || CoursePermissionService.ROLE_TA.equals(role);
    }

    public void requireCanTakeQuiz(Integer courseId, Integer userId) {
        requireActiveMember(courseId, userId);
        if (!coursePermissionService.canTakeQuizzes(courseId, userId)) {
            throw new ApiException(ErrorType.ACCESS_DENIED, "Only students can take quizzes");
        }
    }

    public void requireCanTakeQuiz(HttpServletRequest request, Integer courseId, Integer userId) {
        Object role = request.getAttribute("userRole");
        if (com.coursistant.lms.shared.enums.RoleEnum.SYSTEM_ADMIN.name().equals(role)
                || com.coursistant.lms.shared.enums.RoleEnum.TENANT_ADMIN.name().equals(role)) {
            throw new ApiException(ErrorType.FORBIDDEN, "Admins cannot take quizzes");
        }
        requireCanTakeQuiz(courseId, userId);
    }

    public boolean isInstructor(Integer courseId, Integer userId) {
        return coursePermissionService.isInstructor(courseId, userId);
    }

    public boolean canGrade(Integer courseId, Integer userId) {
        return coursePermissionService.canGrade(courseId, userId);
    }

    public void requireCanGrade(Integer courseId, Integer userId) {
        requireActiveMember(courseId, userId);
        if (!canGrade(courseId, userId)) {
            throw new ApiException(ErrorType.QUIZ_GRADING_FORBIDDEN);
        }
    }

    public void requireInstructor(Integer courseId, Integer userId) {
        coursePermissionService.requireInstructor(courseId, userId);
    }

    public Quiz requireQuizReadable(HttpServletRequest request, Integer courseId, Integer quizId, Integer userId) {
        requireCourse(courseId);
        boolean staff = isStaffViewer(request, courseId, userId);
        Quiz quiz = quizMapper.selectByCourseIdAndId(courseId, quizId);
        if (quiz == null) {
            throw new ApiException(ErrorType.QUIZ_NOT_FOUND);
        }
        if (!staff && !QuizConstants.STATE_PUBLISHED.equals(quiz.getState())) {
            throw new ApiException(ErrorType.QUIZ_NOT_FOUND);
        }
        return quiz;
    }

    public Quiz requireQuizConfigurable(Integer courseId, Integer quizId, Integer userId) {
        requireCourseWritable(courseId, userId);
        Quiz quiz = quizMapper.selectByCourseIdAndId(courseId, quizId);
        if (quiz == null) {
            throw new ApiException(ErrorType.QUIZ_NOT_FOUND);
        }
        return quiz;
    }

    public boolean isGradingTa(Integer courseId, Integer userId, Integer quizId) {
        if (!coursePermissionService.isTa(courseId, userId)) {
            return false;
        }
        if (!canGrade(courseId, userId)) {
            return false;
        }
        return quizAttemptMapper.countByQuizIdAndUserIdAny(quizId, userId) == 0;
    }

    public void requireGradingAccess(Integer courseId, Integer quizId, Integer userId) {
        if (isInstructor(courseId, userId)) {
            return;
        }
        if (!isGradingTa(courseId, userId, quizId)) {
            if (coursePermissionService.isTa(courseId, userId)
                    && quizAttemptMapper.countByQuizIdAndUserIdAny(quizId, userId) > 0) {
                throw new ApiException(ErrorType.QUIZ_TA_SELF_CONFLICT);
            }
            throw new ApiException(ErrorType.QUIZ_GRADING_FORBIDDEN);
        }
    }

    public Course requireGradingWritable(Integer courseId, Integer userId) {
        Course course = requireCourse(courseId);
        requireCanGrade(courseId, userId);
        if (!isGradingWritable(course)) {
            throw new ApiException(ErrorType.COURSE_ARCHIVED,
                    "Grading closed " + ARCHIVE_GRADING_DAYS + " days after the course was archived");
        }
        return course;
    }

    public Course requireReleaseWritable(Integer courseId, Integer userId) {
        Course course = requireCourse(courseId);
        requireInstructor(courseId, userId);
        if (!isGradingWritable(course)) {
            throw new ApiException(ErrorType.COURSE_ARCHIVED,
                    "Grading closed " + ARCHIVE_GRADING_DAYS + " days after the course was archived");
        }
        return course;
    }

    public boolean isGradingWritable(Course course) {
        if (!QuizConstants.COURSE_ARCHIVED.equals(course.getState())) {
            return true;
        }
        LocalDateTime deadline = gradingWritableUntil(course);
        return deadline != null && !LocalDateTime.now(ZoneOffset.UTC).isAfter(deadline);
    }

    public LocalDateTime gradingWritableUntil(Course course) {
        if (!QuizConstants.COURSE_ARCHIVED.equals(course.getState()) || course.getArchivedAt() == null) {
            return null;
        }
        return course.getArchivedAt().plusDays(ARCHIVE_GRADING_DAYS);
    }

    public void assertQuizWindowOpen(Quiz quiz) {
        if (!quizTimeSupport.isWindowOpen(quiz.getOpensAt(), quiz.getClosesAt())) {
            throw new ApiException(ErrorType.QUIZ_WINDOW_CLOSED);
        }
    }

    public void assertQuizPublished(Quiz quiz) {
        if (!QuizConstants.STATE_PUBLISHED.equals(quiz.getState())) {
            throw new ApiException(ErrorType.QUIZ_NOT_PUBLISHED);
        }
    }

    public void requireNewActivityEnabled() {
        if (!QuizFeatureFlags.allowNewActivity) {
            throw new ApiException(ErrorType.QUIZ_FEATURE_DISABLED);
        }
    }

    public boolean isStudentActive(Integer courseId, Integer userId) {
        Enrollment e = coursePermissionService.requireActiveEnrollment(courseId, userId);
        return CoursePermissionService.ROLE_STUDENT.equals(e.getCourseRole());
    }
}
