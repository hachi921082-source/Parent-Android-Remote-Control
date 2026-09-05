package com.familyremote.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.familyremote.app.child.ChildHomeFragment
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityMainBinding
import com.familyremote.app.parent.ParentHomeFragment

/**
 * Single top-level host for the two role tabs (parent mode / child mode), per docs/plans/README.md
 * §2. Parent mode is the default tab shown on launch (spec.md §4). Hosted in a [ViewPager2] (issue
 * #47) so the tabs can be swiped between as well as tapped in [ActivityMainBinding.bottomNavigation]
 * -- the two stay in sync via [ViewPager2.registerOnPageChangeCallback] (pager -> nav) and
 * [android.view.View]'s selected-item listener (nav -> pager), each guarded against re-triggering
 * the other when the change already originated from it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()

        binding.tabFragmentContainer.adapter = TabPagerAdapter(this)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val position = when (item.itemId) {
                R.id.nav_parent -> TAB_PARENT
                R.id.nav_child -> TAB_CHILD
                else -> return@setOnItemSelectedListener false
            }
            binding.tabFragmentContainer.setCurrentItem(position, false)
            true
        }

        binding.tabFragmentContainer.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNavigation.selectedItemId = when (position) {
                    TAB_CHILD -> R.id.nav_child
                    else -> R.id.nav_parent
                }
            }
        })

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_parent
        }
    }

    /** Backs [binding]'s ViewPager2 -- always exactly the two role tabs, in tab order, per
     * docs/plans/README.md §2. */
    private class TabPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_CHILD -> ChildHomeFragment()
            else -> ParentHomeFragment()
        }
    }

    companion object {
        private const val TAB_PARENT = 0
        private const val TAB_CHILD = 1
    }
}
