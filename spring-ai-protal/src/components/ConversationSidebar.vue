<script setup>
import { PlusIcon, ChatBubbleLeftRightIcon } from '@heroicons/vue/24/outline'
defineProps({ title: String, items: { type: Array, default: () => [] }, currentId: String, busy: Boolean, loading: Boolean })
defineEmits(['new', 'select'])
</script>
<template>
  <aside class="conversation-sidebar">
    <div class="sidebar-title"><h2>{{ title }}</h2><small>你的专属空间</small></div>
    <button class="new-conversation" :disabled="busy" @click="$emit('new')"><PlusIcon /> 开始新对话</button>
    <p class="history-caption">最近会话</p>
    <div class="conversation-list" aria-label="历史会话">
      <p v-if="loading" class="loading-line"><span class="spinner"></span>加载会话…</p>
      <p v-else-if="!items.length" class="empty-history">还没有会话，<br>从一个新问题开始吧。</p>
      <button v-for="item in items" :key="item.id" class="conversation-item" :class="{ active: currentId === item.id }" :disabled="busy" :title="item.title" @click="$emit('select', item.id)"><ChatBubbleLeftRightIcon /><span>{{ item.title }}</span></button>
    </div>
    <p class="sidebar-bottom">留住每一次思考。<br>历史对话会自动保存。</p>
  </aside>
</template>
