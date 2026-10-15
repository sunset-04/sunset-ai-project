<script setup>
import { ArrowUpRightIcon, ArrowRightIcon, ChatBubbleLeftRightIcon, DocumentTextIcon, LifebuoyIcon, HeartIcon, SparklesIcon } from '@heroicons/vue/24/outline'
const apps = [
  { title: 'AI 对话', label: 'CHAT', description: '梳理思路、理解代码，或者聊聊今天的新想法。', route: '/ai-chat', icon: ChatBubbleLeftRightIcon, tag: '多模态交流', tint: 'sage' },
  { title: '文档问答', label: 'DOCUMENTS', description: '上传一份 PDF，让长篇资料变成有来有回的对话。', route: '/chat-pdf', icon: DocumentTextIcon, tag: '阅读更轻松', tint: 'sand' },
  { title: '智能客服', label: 'ASSISTANT', description: '了解课程与校区，找到合适的方向，预约试听。', route: '/customer-service', icon: LifebuoyIcon, tag: '课程与预约', tint: 'blue' },
  { title: '哄哄模拟器', label: 'PLAYGROUND', description: '换个轻松的场景，在对话中练习表达与沟通。', route: '/game', icon: HeartIcon, tag: '趣味互动', tint: 'rose' },
]
</script>
<template>
  <div class="dashboard">
    <div class="dashboard-heading"><div><p class="eyebrow">A LITTLE SPACE FOR BIG IDEAS</p><h1>你好，欢迎来到 sunset<span class="heading-dot">.</span></h1><p class="subtext">给灵感留一点空间，让日常多一位助手。</p></div><span class="outline-badge"><SparklesIcon /> 与 AI 一起探索</span></div>
    <section class="welcome-banner">
      <div class="welcome-copy"><span class="pill">你的日常 AI 工作台</span><h2>把复杂的事，<br>聊得简单一点。</h2><p>从一个问题、一份文档开始。<br>思考、阅读与探索，都在这里发生。</p><RouterLink to="/ai-chat" class="button primary">开始对话 <ArrowRightIcon /></RouterLink></div>
      <div class="banner-art" aria-hidden="true"><div class="art-orbit orbit-one"></div><div class="art-orbit orbit-two"></div><div class="art-sun"></div><div class="art-land land-back"></div><div class="art-land land-front"></div><span class="art-caption">a fresh perspective, every day.</span><span class="art-star">✳</span></div>
    </section>
    <div class="section-heading"><div><h2>从这里开始</h2><p>选择一个助手，进入你的专属空间。</p></div><span class="section-note">为每一种好奇心</span></div>
    <div class="app-cards"><RouterLink v-for="app in apps" :key="app.route" :to="app.route" class="app-card"><div class="card-top"><span class="app-icon" :class="app.tint"><component :is="app.icon" /></span><ArrowUpRightIcon class="card-arrow" /></div><span class="app-label">{{ app.label }}</span><h3>{{ app.title }}</h3><p>{{ app.description }}</p><div class="card-bottom"><span>{{ app.tag }}</span><ArrowRightIcon /></div></RouterLink></div>
    <section class="inspiration-row"><span class="inspiration-mark">✳</span><div><strong>不知道从哪里开始？</strong><p>试着问「帮我制定一份 Java 学习计划」，或上传一份 PDF，让 AI 帮你提炼重点。</p></div><RouterLink to="/chat-pdf">读一份文档 <ArrowUpRightIcon /></RouterLink></section>
    <footer class="dashboard-footer"><span>sunset AI workspace</span><span>保持好奇，让想法生长。</span></footer>
  </div>
</template>
