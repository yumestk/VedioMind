package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class CourseService {
    public record Course(Long id, String title, String description, String status, long structureVersion, boolean canManage) {}
    public record Material(Long id, Long mediaId, String title, int position) {}
    public record Section(Long id, String title, int position, List<Material> materials) {}
    public record Detail(Course course, List<Section> sections) {}
    private final JdbcTemplate jdbc;
    private final StudyAccessService access;

    public CourseService(JdbcTemplate jdbc, StudyAccessService access) { this.jdbc = jdbc; this.access = access; }

    public List<Course> list(Long userId) {
        return jdbc.query("SELECT * FROM courses WHERE author_id=? OR status='PUBLISHED' ORDER BY updated_at DESC,id DESC",
                (rs, n) -> new Course(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                        rs.getString("status"), rs.getLong("structure_version"), userId.equals(rs.getLong("author_id"))), userId);
    }

    public Detail detail(Long id, Long userId) {
        Course course = readable(id, userId);
        var sections = jdbc.query("SELECT * FROM course_sections WHERE course_id=? ORDER BY position,id", (rs, n) -> {
            long sectionId = rs.getLong("id");
            var materials = jdbc.query("SELECT cm.id,cm.media_id,m.filename,cm.position FROM course_materials cm "
                    + "JOIN media_files m ON m.id=cm.media_id WHERE cm.course_id=? AND cm.section_id=? "
                    + "AND m.deleted_at IS NULL ORDER BY cm.position,cm.id", (row, index) ->
                    new Material(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4)), id, sectionId);
            return new Section(sectionId, rs.getString("title"), rs.getInt("position"), materials);
        }, id);
        return new Detail(course, sections);
    }

    @Transactional
    public Detail create(Long userId, String title, String description) {
        String normalized = title(title);
        String desc = description == null ? "" : description.trim();
        if (desc.length() > 2000) throw new IllegalArgumentException("课程简介不能超过 2000 字");
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO courses(author_id,title,description) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId); statement.setString(2, normalized); statement.setString(3, desc);
            return statement;
        }, key);
        return detail(key.getKey().longValue(), userId);
    }

    @Transactional
    public Detail addSection(Long id, Long userId, String title, int position) {
        manage(id, userId);
        jdbc.update("INSERT INTO course_sections(course_id,title,position) VALUES (?,?,?)", id, title(title), position(position));
        changed(id);
        return detail(id, userId);
    }

    @Transactional
    public Detail addMaterial(Long id, Long sectionId, Long mediaId, Long userId, int position) {
        manage(id, userId);
        access.requireAuthor(mediaId, userId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM course_sections WHERE id=? AND course_id=?", Integer.class, sectionId, id) != 1)
            throw new NoSuchElementException("Section not found");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM course_materials WHERE course_id=? AND media_id=?", Integer.class, id, mediaId) > 0)
            throw new IllegalArgumentException("视频已在课程中");
        jdbc.update("INSERT INTO course_materials(course_id,section_id,media_id,position) VALUES (?,?,?,?)", id, sectionId, mediaId, position(position));
        changed(id);
        return detail(id, userId);
    }

    @Transactional
    public Detail removeMaterial(Long id, Long materialId, Long userId) {
        manage(id, userId);
        if (jdbc.update("DELETE FROM course_materials WHERE id=? AND course_id=?", materialId, id) != 1)
            throw new NoSuchElementException("Material not found");
        changed(id);
        access.stopUnavailableRuns();
        return detail(id, userId);
    }

    @Transactional
    public Detail publish(Long id, Long userId, boolean published) {
        manage(id, userId);
        if (published && jdbc.queryForObject("SELECT COUNT(*) FROM course_materials cm JOIN media_files m ON m.id=cm.media_id "
                + "WHERE cm.course_id=? AND m.deleted_at IS NULL", Integer.class, id) == 0)
            throw new IllegalArgumentException("请先添加课程视频");
        jdbc.update("UPDATE courses SET status=? WHERE id=?", published ? "PUBLISHED" : "DRAFT", id);
        changed(id);
        access.stopUnavailableRuns();
        return detail(id, userId);
    }

    private Course readable(Long id, Long userId) {
        return jdbc.query("SELECT * FROM courses WHERE id=? AND (author_id=? OR status='PUBLISHED')", (rs,n) ->
                new Course(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getString("status"),
                        rs.getLong("structure_version"), userId.equals(rs.getLong("author_id"))), id, userId)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("Course not found"));
    }

    private void manage(Long id, Long userId) {
        // Lock the course before taking the transaction snapshot and serialize structure mutations.
        var owners = jdbc.queryForList("SELECT author_id FROM courses WHERE id=? FOR UPDATE", Long.class, id);
        if (owners.isEmpty() || !userId.equals(owners.getFirst())) throw new NoSuchElementException("Course not found");
    }

    private void changed(Long id) {
        jdbc.update("UPDATE courses SET structure_version=structure_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }
    private String title(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) throw new IllegalArgumentException("标题须为 1–100 字");
        return value.trim();
    }
    private int position(int value) {
        if (value < 0 || value > 100000) throw new IllegalArgumentException("无效排序位置");
        return value;
    }
}
