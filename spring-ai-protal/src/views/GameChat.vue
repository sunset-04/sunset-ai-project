<template>
  <div class="game-chat" >
    <div class="game-container">
      <!-- 游戏开始界面 -->
      <div v-if="!isGameStarted" class="game-start">
        <h2>哄哄模拟器</h2>
        <div class="input-area">
          <textarea
            v-model="angerReason"
            placeholder="请输入女友生气的原因（可选）..."
            rows="3"
          ></textarea>
          <button class="start-button" @click="startGame">
            开始游戏
          </button>
        </div>
      </div>

      <!-- 聊天界面 -->
      <div v-else class="chat-main">
        <!-- 游戏统计信息 -->
        <div class="game-stats">
          <div class="stat-item">
            <span class="label">
              <HeartIcon class="heart-icon" :class="{ 'beating': forgiveness >= 100 }" />
              女友原谅值
            </span>
            <div class="progress-bar">
              <div 
                class="progress" 
                :style="{ width: `${forgiveness}%` }"
                :class="{ 
                  'low': forgiveness < 30,
                  'medium': forgiveness >= 30 && forgiveness < 70,
                  'high': forgiveness >= 70 
                }"
              ></div>
            </div>
            <span class="value">{{ forgiveness }}%</span>
          </div>
          <div class="stat-item">
            <span class="label">对话轮次</span>
            <span class="value">{{ currentRound }}/{{ MAX_ROUNDS }}</span>
          </div>
        </div>

        <div class="messages" ref="messagesRef">
          <ChatMessage
            v-for="(message, index) in currentMessages"
            :key="index"
            :message="message"
            :is-stream="isStreaming && index === currentMessages.length - 1"
          />
        </div>
        
        <div class="input-area">
          <textarea
            v-model="userInput"
            @keydown.enter.exact="(event) => { if (!event.isComposing) { event.preventDefault(); sendMessage() } }"
            placeholder="输入消息..."
            rows="1"
            ref="inputRef"
            :disabled="isGameOver"
          ></textarea>
          <button 
            class="send-button" 
            @click="sendMessage()"
            :disabled="isStreaming || !userInput.trim() || isGameOver"
          >
            <PaperAirplaneIcon class="icon" />
          </button>
        </div>
      </div>

      <!-- 游戏结束提示 -->
      <div v-if="isGameOver" class="game-over" :class="{ 'success': forgiveness >= 100 }">
        <div class="result">{{ gameResult }}</div>
        <button class="restart-button" @click="resetGame">
          重新开始
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, computed } from 'vue'
import { PaperAirplaneIcon, HeartIcon } from '@heroicons/vue/24/outline'
import ChatMessage from '../components/ChatMessage.vue'
import { chatAPI } from '../services/api'

const messagesRef = ref(null)
const inputRef = ref(null)
const userInput = ref('')
const isStreaming = ref(false)
const currentChatId = ref(null)
const currentMessages = ref([])
const angerReason = ref('')
const isGameStarted = ref(false)
const isGameOver = ref(false)
const gameResult = ref('')
const MAX_ROUNDS = 10  // 添加最大轮次常量
const currentRound = ref(0)  // 添加当前轮次计数
const forgiveness = ref(0)

// 自动调整输入框高度
const adjustTextareaHeight = () => {
  const textarea = inputRef.value
  if (textarea) {
    textarea.style.height = 'auto'
    textarea.style.height = textarea.scrollHeight + 'px'
  }
}

// 滚动到底部
const scrollToBottom = async () => {
  await nextTick()
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

// 开始游戏
const startGame = async () => {
  isGameStarted.value = true
  isGameOver.value = false
  gameResult.value = ''
  currentChatId.value = Date.now().toString()
  currentMessages.value = []
  currentRound.value = 0
  forgiveness.value = 0  // 重置原谅值
  
  // 发送开始游戏请求
  const startPrompt = angerReason.value 
    ? `开始游戏，女友生气原因：${angerReason.value}`
    : '随机生成生气原因，并开始游戏'
  
  await sendMessage(startPrompt)
}

// 重置游戏
const resetGame = () => {
  isGameStarted.value = false
  isGameOver.value = false
  gameResult.value = ''
  currentMessages.value = []
  angerReason.value = ''
  userInput.value = ''
  currentRound.value = 0
  forgiveness.value = 0
}

// 发送消息
const sendMessage = async (content) => {
  if (isStreaming.value || (!content && !userInput.value.trim())) return
  
  // 使用传入的 content 或用户输入框的内容
  const messageContent = content || userInput.value.trim()
  
  // 添加用户消息
  const userMessage = {
    role: 'user',
    content: messageContent,
    timestamp: new Date()
  }
  currentMessages.value.push(userMessage)
  
  // 清空输入并增加轮次计数
  if (!content) {  // 只有在非传入内容时才清空输入框和计数
    userInput.value = ''
    adjustTextareaHeight()
    currentRound.value++  // 增加轮次计数
  }
  await scrollToBottom()
  
  // 添加助手消息占位
  const assistantMessage = {
    role: 'assistant',
    content: '',
    timestamp: new Date()
  }
  currentMessages.value.push(assistantMessage)
  isStreaming.value = true
  
  let accumulatedContent = ''
  
  try {
    // 确保使用正确的消息内容发送请求
    const reader = await chatAPI.sendGameMessage(messageContent, currentChatId.value)
    const decoder = new TextDecoder('utf-8')
    
    while (true) {
      try {
        const { value, done } = await reader.read()
        if (done) break
        
        // 累积新内容
        accumulatedContent += decoder.decode(value, { stream: true })
        
        // 尝试从回复中提取原谅值
        const forgivenessMatch = accumulatedContent.match(/原谅值[:：]\s*(\d+)/i)
        if (forgivenessMatch) {
          const newForgiveness = parseInt(forgivenessMatch[1])
          if (!isNaN(newForgiveness)) {
            forgiveness.value = Math.min(100, Math.max(0, newForgiveness))
            
            // 检查游戏胜利或失败条件
            if (forgiveness.value >= 100) {
              isGameOver.value = true
              gameResult.value = '恭喜你！成功哄好了女友！💕'
            } else if (forgiveness.value <= 0) {
              isGameOver.value = true
              gameResult.value = '游戏失败：原谅值降到0，女友更生气了！😢'
          }
        }
        }
        // 更新消息内容
        await nextTick(() => {
          const updatedMessage = {
            ...assistantMessage,
            content: accumulatedContent
          }
          const lastIndex = currentMessages.value.length - 1
          currentMessages.value.splice(lastIndex, 1, updatedMessage)
        })
        await scrollToBottom()
      } catch (readError) {
        console.error('读取流错误:', readError)
        throw readError
      }
    }

    // 检查是否达到最大轮次，并等待本轮回复完成后再判断
    if (currentRound.value >= MAX_ROUNDS) {
      isGameOver.value = true
      if (forgiveness.value >= 100) {
        gameResult.value = '恭喜你！在最后一轮成功哄好了女友！💕'
      } else {
        gameResult.value = `游戏结束：对话轮次已达上限(${MAX_ROUNDS}轮)，当前原谅值为${forgiveness.value}，很遗憾没能完全哄好女友`
      }
    }
    // 检查是否游戏结束
    else if (accumulatedContent.includes('游戏结束')) {
      isGameOver.value = true
      gameResult.value = accumulatedContent
    }
  } catch (error) {
    console.error('发送消息失败:', error)
    assistantMessage.content = error.message || '抱歉，发生了错误，请稍后重试。'
    currentMessages.value.splice(currentMessages.value.length - 1, 1, { ...assistantMessage })
  } finally {
    isStreaming.value = false
    await scrollToBottom()
  }
}

// 添加计算属性显示剩余轮次
const remainingRounds = computed(() => MAX_ROUNDS - currentRound.value)

onMounted(() => {
  adjustTextareaHeight()
})
</script>

<style scoped>
.game-chat{height:calc(100dvh - 72px);padding:24px;background:var(--canvas);color:var(--ink)}.game-container{height:100%;max-width:1000px;margin:auto;display:flex;flex-direction:column;position:relative;background:var(--surface);border:1px solid var(--line);border-radius:14px;overflow:hidden}.game-start{margin:auto;width:min(90%,540px);padding:40px 20px;text-align:center}.game-start:before{content:'♡';display:grid;place-items:center;background:#f8eaeb;color:#ba828b;border-radius:20px;width:64px;height:64px;font-size:35px;margin:0 auto 22px}.game-start h2{font-size:27px;font-weight:550;margin-bottom:28px}.input-area{display:flex;gap:10px;padding:18px 24px;border-top:1px solid var(--line)}.game-start .input-area{flex-direction:column;padding:0;border:0}.input-area textarea{flex:1;resize:none;border:1px solid var(--line);border-radius:10px;background:var(--canvas);color:var(--ink);padding:14px;font-size:12px;line-height:1.9;outline-color:var(--accent)}.game-start textarea{min-height:120px}.start-button,.restart-button{padding:13px 24px;background:var(--accent);color:var(--surface);border-radius:9px;font-size:12px}.chat-main{display:flex;flex-direction:column;flex:1;min-height:0}.game-stats{display:flex;align-items:center;gap:30px;padding:19px 24px;border-bottom:1px solid var(--line);font-size:11px}.stat-item{display:flex;align-items:center;gap:10px}.stat-item:first-child{flex:1}.label{display:flex;gap:7px;align-items:center;color:var(--muted)}.heart-icon{width:18px;height:18px;color:#ba828b}.progress-bar{height:6px;background:var(--soft);border-radius:20px;flex:1;max-width:200px;overflow:hidden}.progress{height:100%;background:#a5c6a2;transition:width .3s}.progress.low{background:#cb9a9a}.value{color:var(--muted)}.messages{flex:1;min-height:0;overflow:auto;padding:28px 40px}.game-over{padding:18px;display:flex;justify-content:center;align-items:center;gap:20px;border-top:1px solid var(--line);background:var(--soft);font-size:13px}.game-over.success{background:var(--accent-soft)}.send-button{align-self:flex-end}@media(max-width:640px){.game-chat{height:calc(100dvh - 60px);padding:10px}.game-stats{gap:12px;padding:15px 12px;font-size:10px}.messages{padding:22px 16px}.input-area{padding:14px}.progress-bar{max-width:70px}.game-over{flex-direction:column}}
</style>
