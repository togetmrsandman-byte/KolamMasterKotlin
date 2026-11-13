import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

serve(async (req) => {
  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) {
      return new Response(JSON.stringify({ error: 'Missing authorization header' }), { status: 401, headers: { 'Content-Type': 'application/json' } })
    }
    const token = authHeader.replace('Bearer ', '')

    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ''
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') ?? ''
    const supabaseServiceKey = Deno.env.get('ACCOUNT_DELETE_SERVICE_KEY') ?? ''
    const workerBaseUrl = Deno.env.get('WORKER_BASE_URL') ?? 'https://kolam-master-backend.togetmrsandman.workers.dev'
    const workerSecret = Deno.env.get('WORKER_SECRET') ?? ''

    if (!supabaseUrl || !supabaseServiceKey) {
      return new Response(JSON.stringify({ error: 'Server configuration error' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    }

    // Verify user JWT and get authenticated userId
    const userClient = createClient(supabaseUrl, supabaseAnonKey, {
      auth: { persistSession: false }
    })
    const { data: { user }, error: userError } = await userClient.auth.getUser(token)

    if (userError || !user) {
      return new Response(JSON.stringify({ error: 'Invalid or expired session' }), { status: 401, headers: { 'Content-Type': 'application/json' } })
    }

    const userId = user.id

    // Admin client using service role key
    const adminClient = createClient(supabaseUrl, supabaseServiceKey, {
      auth: { persistSession: false },
      global: {
        headers: {
          Authorization: `Bearer ${supabaseServiceKey}`
        }
      }
    })

    // 1. Retrieve actual R2 object keys associated with the user's gallery submissions & contact messages
    const { data: submissions } = await adminClient
      .from('gallery_submissions')
      .select('id, gallery_submission_images(image_key)')
      .eq('user_id', userId)

    const objectKeys: string[] = []
    if (submissions) {
      for (const sub of submissions) {
        if (sub.gallery_submission_images) {
          for (const img of sub.gallery_submission_images) {
            if (img.image_key) objectKeys.push(img.image_key)
          }
        }
      }
    }

    const { data: convs } = await adminClient
      .from('contact_conversations')
      .select('id')
      .eq('user_id', userId)

    const conversationIds = convs ? convs.map((c: any) => c.id) : []

    const { data: messages } = conversationIds.length > 0 ? await adminClient
      .from('contact_messages')
      .select('image_url')
      .in('conversation_id', conversationIds) : { data: [] }

    if (messages) {
      for (const msg of messages) {
        if (msg.image_url) {
          objectKeys.push(msg.image_url)
        }
      }
    }

    // 2. Secure deletion of those exact objects from Cloudflare R2 / Worker storage (safe to retry / tolerate 404)
    if (objectKeys.length > 0) {
      try {
        const cleanupRes = await fetch(`${workerBaseUrl}/chat/delete-images`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(workerSecret ? { 'X-Worker-Secret': workerSecret } : {})
          },
          body: JSON.stringify({ userId, objectKeys })
        })
        if (!cleanupRes.ok && cleanupRes.status !== 404) {
          const errBody = await cleanupRes.text()
          console.error("Storage cleanup failed with status:", cleanupRes.status, "body:", errBody)
          return new Response(JSON.stringify({ error: `Storage cleanup failed: ${errBody}` }), { status: 500, headers: { 'Content-Type': 'application/json' } })
        }
      } catch (storageErr) {
        console.error("Storage cleanup exception:", storageErr)
        return new Response(JSON.stringify({ error: `Storage cleanup error: ${storageErr.message}` }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      }
    }

    // 3. Wait for storage deletion to succeed before deleting database records
    const { error: userDataError } = await adminClient.from('user_data').delete().eq('user_id', userId)
    if (userDataError) throw userDataError

    const { error: pushTokensError } = await adminClient.from('push_tokens').delete().eq('user_id', userId)
    if (pushTokensError) throw pushTokensError

    const { error: galleryError } = await adminClient.from('gallery_submissions').delete().eq('user_id', userId)
    if (galleryError) throw galleryError

    if (conversationIds.length > 0) {
      const { error: messagesError } = await adminClient
        .from('contact_messages')
        .delete()
        .in('conversation_id', conversationIds)
      if (messagesError) throw messagesError
    }

    const { error: convsError } = await adminClient
      .from('contact_conversations')
      .delete()
      .eq('user_id', userId)
    if (convsError) throw convsError

    // 4. Only after storage and database cleanup succeeds, delete Supabase Auth user record
    const { error: deleteAuthError } = await adminClient.auth.admin.deleteUser(userId)
    if (deleteAuthError) throw deleteAuthError

    return new Response(JSON.stringify({ success: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  } catch (err) {
    console.error("Account deletion failed:", {
      name: err?.name,
      message: err?.message,
      stack: err?.stack
    })
    return new Response(JSON.stringify({ error: 'Internal server error' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' }
    })
  }
})
