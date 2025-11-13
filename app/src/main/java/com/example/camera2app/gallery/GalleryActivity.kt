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

    // 갤러리 사진들 URI 리스트
    private val photos = mutableListOf<Uri>()

    // 상단 무드보드 헤더
    private val headers = listOf(
        MoodCard("추천 스타일", null),
        MoodCard("나의 무드보드", null)
    )

    // 권한 요청 런처
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadMediaIfGranted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ─────────────────────
        // 헤더: 수평 무드보드 리스트
        // ─────────────────────
        binding.headerList.apply {
            layoutManager = LinearLayoutManager(
                this@GalleryActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = MoodAdapter(headers)
        }

        // ─────────────────────
        // 사진 그리드: 3열
        // ─────────────────────
        binding.photoGrid.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = PhotoAdapter(photos) { uri ->
                // 사진 클릭 → 우리 PreviewActivity 로 이동
                val intent = Intent(this@GalleryActivity, PreviewActivity::class.java)
                intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, uri.toString())
                startActivity(intent)
            }
            // 사진 사이 간격
            addItemDecoration(GridSpacing(3, dp(2), includeEdge = false))
        }

        // 닫기 버튼
        binding.btnClose.setOnClickListener { finish() }

        // 권한 확인 후 로딩
        ensurePermissionThenLoad()
    }

    // ─────────────────────
    // 권한 체크 및 요청
    // ─────────────────────
    private fun ensurePermissionThenLoad() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val granted = perms.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (granted) {
            loadPhotos()
        } else {
            requestPerm.launch(perms)
        }
    }

    private fun loadMediaIfGranted() {
        val ok = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

        if (ok) loadPhotos()
    }

    // ─────────────────────
    // MediaStore 에서 사진 불러오기
    // ─────────────────────
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
                val uri = ContentUris.withAppendedId(collection, id)
                photos += uri
            }
        }

        binding.photoGrid.adapter?.notifyDataSetChanged()
        binding.headerList.visibility = View.VISIBLE
    }

    // dp → px 변환
    private fun dp(v: Int) =
        (resources.displayMetrics.density * v + 0.5f).toInt()

    // ─────────────────────
    // 데이터 클래스
    // ─────────────────────
    data class MoodCard(val title: String, val image: Uri?)

    // ─────────────────────
    // 어댑터 & 뷰홀더들
    // ─────────────────────
    class MoodAdapter(private val items: List<MoodCard>) :
        RecyclerView.Adapter<MoodVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MoodVH {
            val b = ItemMoodboardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return MoodVH(b)
        }

        override fun onBindViewHolder(holder: MoodVH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    class MoodVH(private val b: ItemMoodboardBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(m: MoodCard) {
            b.title.text = m.title
            if (m.image != null) {
                Glide.with(b.cover)
                    .load(m.image)
                    .centerCrop()
                    .into(b.cover)
            } else {
                // 기본 상태 (레이아웃에 설정된 더미 이미지/배경 사용)
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
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PhotoVH(b)
        }

        override fun onBindViewHolder(holder: PhotoVH, position: Int) {
            holder.bind(data[position], onClick)
        }

        override fun getItemCount() = data.size
    }

    class PhotoVH(private val b: ItemPhotoBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(uri: Uri, onClick: (Uri) -> Unit) {
            Glide.with(b.thumb)
                .load(uri)
                .centerCrop()
                .into(b.thumb)

            b.root.setOnClickListener { onClick(uri) }
        }
    }

    // ─────────────────────
    // 그리드 아이템 간격
    // ─────────────────────
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
