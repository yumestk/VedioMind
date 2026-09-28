import { createRouter, createWebHistory } from 'vue-router'
import MediaListView from '../views/MediaListView.vue'
import MediaWorkspaceView from '../views/MediaWorkspaceView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'media-list', component: MediaListView },
    { path: '/media/:mediaId', name: 'media-workspace', component: MediaWorkspaceView, props: true },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
})

export default router
