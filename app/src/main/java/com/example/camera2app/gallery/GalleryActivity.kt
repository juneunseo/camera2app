package com.example.camera2app.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityGalleryBinding
import com.example.camera2app.databinding.ItemMoodboardBinding
import com.example.camera2app.databinding.ItemPhotoBinding

class GalleryActivity : ComponentActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val photos = mutableListOf<Uri>()
    private val headers = listOf(
        MoodCard("추천 스타일", null),
        MoodCard("나의 무드보드", null)
    )

    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadMediaIfGranted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 헤더: 수평 리스트
        binding.headerList.apply {
            layoutManager = LinearLayoutManager(
                this@GalleryActivity, LinearLayoutManager.HORIZONTAL, false
            )
            adapter = MoodAdapter(headers)
        }

        // 그리드: 3열
        binding.photoGrid.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = PhotoAdapter(photos) { uri ->
                setResult(RESULT_OK, Intent().setData(uri))
                finish()
            }
            addItemDecoration(GridSpacing(3, dp(2), includeEdge = false))
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSettings.setOnClickListener { /* TODO: 설정 화면 */ }
        binding.btnCamera.setOnClickListener {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }

        ensurePermissionThenLoad()
    }

    private fun ensurePermissionThenLoad() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val granted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) loadPhotos() else requestPerm.launch(perms)
    }

    private fun loadMediaIfGranted() {
        val ok = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        if (ok) loadPhotos()
    }

    private fun loadPhotos() {
        photos.clear()

        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, proj, null, null, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                photos += ContentUris.withAppendedId(collection, id)
            }
        }

        binding.photoGrid.adapter?.notifyDataSetChanged()
        binding.headerList.visibility = View.VISIBLE
    }

    // --- 작은 유틸 ---
    private fun dp(v: Int) = (resources.displayMetrics.density * v + 0.5f).toInt()

    // --- 데이터 ---
    data class MoodCard(val title: String, val image: Uri?)

    // --- 어댑터들 ---
    class MoodAdapter(private val items: List<MoodCard>) :
        RecyclerView.Adapter<MoodVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MoodVH {
            val b = ItemMoodboardBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MoodVH(b)
        }
        override fun onBindViewHolder(holder: MoodVH, position: Int) =
            holder.bind(items[position])
        override fun getItemCount() = items.size
    }

    class MoodVH(private val b: ItemMoodboardBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(m: MoodCard) {
            b.title.text = m.title
            if (m.image != null) {
                // ✔ 필요 시 헤더 카드의 미리보기 이미지 로드
                Glide.with(b.cover)
                    .load(m.image)
                    .centerCrop()
                    .into(b.cover)
            } else {
                // 이미지 없으면 기본 상태 유지 (아이콘/그라데이션 등 레이아웃에 설정)
                b.cover.setImageDrawable(null)
            }
        }
    }

    class PhotoAdapter(
        private val data: List<Uri>,
        private val onClick: (Uri) -> Unit
    ) : RecyclerView.Adapter<PhotoVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
            val b = ItemPhotoBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return PhotoVH(b)
        }
        override fun onBindViewHolder(holder: PhotoVH, position: Int) =
            holder.bind(data[position], onClick)
        override fun getItemCount() = data.size
    }

    class PhotoVH(private val b: ItemPhotoBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(uri: Uri, onClick: (Uri) -> Unit) {
            // ✅ 바로 여기! 썸네일 로드
            Glide.with(b.thumb)
                .load(uri)
                .centerCrop()
                .into(b.thumb)

            b.root.setOnClickListener { onClick(uri) }
        }
    }

    /** 간단한 그리드 간격 */
    class GridSpacing(
        private val spanCount: Int,
        private val spacingPx: Int,
        private val includeEdge: Boolean
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view)
            val col = pos % spanCount
            if (includeEdge) {
                outRect.left = spacingPx - col * spacingPx / spanCount
                outRect.right = (col + 1) * spacingPx / spanCount
                if (pos < spanCount) outRect.top = spacingPx
                outRect.bottom = spacingPx
            } else {
                outRect.left = col * spacingPx / spanCount
                outRect.right = spacingPx - (col + 1) * spacingPx / spanCount
                if (pos >= spanCount) outRect.top = spacingPx
            }
        }
    }
}
