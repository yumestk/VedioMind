import { createRouter, createWebHistory } from 'vue-router'
import MediaListView from '../views/MediaListView.vue'
import CoursesView from '../views/CoursesView.vue'
import CourseDetailView from '../views/CourseDetailView.vue'
import StudyHistoryView from '../views/StudyHistoryView.vue'
import MediaWorkspaceView from '../views/MediaWorkspaceView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'media-list', component: MediaListView },
    { path: '/media/:mediaId', name: 'media-workspace', component: MediaWorkspaceView, props: true },
    { path: '/courses', component: CoursesView },
    { path: '/courses/:courseId', component: CourseDetailView, props: true },
    { path: '/courses/:courseId/media/:mediaId', component: MediaWorkspaceView, props: true },
    { path: '/study-history', component: StudyHistoryView },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
})

export default router
