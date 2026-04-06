package org.orbitmvi.orbit.sample.posts.compose.multiplatform

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.orbitmvi.orbit.sample.posts.compose.multiplatform.domain.viewmodel.list.PostListViewModel

object ViewModelHelper : KoinComponent {
    val postListViewModel: PostListViewModel by inject()
}
