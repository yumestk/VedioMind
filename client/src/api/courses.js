import http from './http'
export const listCourses = async () => (await http.get('/courses')).data
export const getCourse = async id => (await http.get(`/courses/${id}`)).data
export const createCourse = async body => (await http.post('/courses', body)).data
export const addCourseSection = async (id, body) => (await http.post(`/courses/${id}/sections`, body)).data
export const addCourseMaterial = async (id, sectionId, body) => (await http.post(`/courses/${id}/sections/${sectionId}/materials`, body)).data
export const removeCourseMaterial = async (id, materialId) => (await http.delete(`/courses/${id}/materials/${materialId}`)).data
export const setCourseVisibility = async (id, published) => (await http.put(`/courses/${id}/visibility`, { published })).data
export const listMyConversations = async () => (await http.get('/conversations')).data
